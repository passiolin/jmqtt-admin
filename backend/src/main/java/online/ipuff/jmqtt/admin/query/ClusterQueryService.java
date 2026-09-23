/*
 * Copyright (c) 2026 ipuff.online
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package online.ipuff.jmqtt.admin.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.ipuff.jmqtt.admin.AdminProperties;
import online.ipuff.jmqtt.admin.redis.AdminKeys;
import online.ipuff.jmqtt.admin.redis.AdminRedis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 集群视图查询。全部数据来自 Redis, 不产生任何对 broker 的调用。
 *
 * <h2>列表为什么是游标式而不是分页式</h2>
 * 客户端注册表与过滤器视图在 Redis 上是 hash, 只能 HSCAN。HSCAN 的语义是
 * 「从游标处继续扫, 直到扫完」, 它<b>没有偏移量概念</b> —— 硬要造一个
 * 「第 3 页」需要把前面所有页都扫一遍, 代价随页码线性增长。
 * 所以接口如实暴露游标, 前端用「加载更多」而不是「第 N 页」。
 *
 * <h2>跨节点聚合为什么必须显式标注截断</h2>
 * 当不指定节点时, 每个节点各取前 N 条再合并。这意味着<b>结果不是「全集群前 N 条」</b>:
 * 某个节点第 N+1 条可能比另一个节点的第 1 条更「靠前」(按任何排序)。所以合并结果
 * 一定要带上「每节点取了多少 / 是否被截断」, 否则操作者会把它当成全局排序的完整视图。
 */
@Service
public class ClusterQueryService {

    private static final Logger log = LoggerFactory.getLogger(ClusterQueryService.class);

    /**
     * 聚合类查询(跨节点重叠检测、按订阅数排序)的扫描上限。
     *
     * <p>它存在的原因是控制台不能变成一个「把 Redis 拖慢的工具」:
     * 十万级过滤器下一次完整聚合扫描是实打实的开销, 而这些查询本身只是给人看的。
     * 到上限就停止并如实标注「已截断」, 比「看起来完整其实不全」安全。
     */
    private static final int AGGREGATE_SCAN_LIMIT = 50_000;

    /** HSCAN 一次请求最多迭代多少轮, 防止一个请求把 CPU 占满 */
    private static final int MAX_SCAN_ROUNDS = 40;

    private final AdminRedis redis;
    private final AdminKeys keys;
    private final AdminProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClusterQueryService(AdminRedis redis, AdminKeys keys, AdminProperties properties) {
        this.redis = redis;
        this.keys = keys;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // 节点
    // ------------------------------------------------------------------

    /**
     * 全部已知节点。
     *
     * <p>节点集合来自 {@code nodes} 集合(长期存在), 每个节点的存活状态来自它的概要键 ——
     * 两者是分开的, 因此能区分「这个节点曾经存在但已离线」与「从没听说过这个节点」。
     */
    public List<NodeView> nodes() {
        Set<String> nodeIds = new TreeSet<>(redis.smembers(keys.nodes()));
        List<NodeView> views = new ArrayList<>(nodeIds.size());
        for (String nodeId : nodeIds) {
            String summaryKey = keys.node(nodeId);
            Map<String, String> summary = redis.hgetAll(summaryKey);
            long clientEntries = summary.isEmpty() ? -1L : redis.scanHash(
                    keys.clients(nodeId), "0", 1, null).total();
            long filterEntries = summary.isEmpty() ? -1L : redis.scanHash(
                    keys.filters(nodeId), "0", 1, null).total();
            views.add(NodeView.of(nodeId, summary, clientEntries, filterEntries,
                    properties.nodeStaleAfterMs()));
        }
        views.sort(Comparator.comparing(NodeView::node));
        return views;
    }

    /**
     * 各<b>在线</b>节点的连接数快照。
     *
     * <p>驱逐流程的每个推进周期都要读一次, 因此它必须比 {@link #nodes()} 便宜得多:
     * 只取一个字段, 不做 HLEN, 也不构造 NodeView。
     *
     * <p><b>心跳过期的节点会被排除</b>, 而不是按 0 计入。这一点直接决定驱逐校验的正确性:
     * 若把一个已死节点按 0 计入「其他节点」, 它基线上的那些连接会被算成
     * 「迁移到了别的节点」—— 于是「客户端实际掉线了」被误判成「迁移成功」。
     */
    public Map<String, Long> connectionCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String nodeId : new TreeSet<>(redis.smembers(keys.nodes()))) {
            Map<String, String> summary = redis.hgetSome(keys.node(nodeId), "connections", "updatedAt");
            if (summary.isEmpty()) {
                continue;
            }
            long updatedAt = parseLong(summary.get("updatedAt"), 0L);
            if (updatedAt <= 0 || now - updatedAt > properties.nodeStaleAfterMs()) {
                continue;
            }
            counts.put(nodeId, parseLong(summary.get("connections"), 0L));
        }
        return counts;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public NodeView node(String nodeId) {
        String summaryKey = keys.node(nodeId);
        Map<String, String> summary = redis.hgetAll(summaryKey);
        long clientEntries = summary.isEmpty() ? -1L : redis.scanHash(
                keys.clients(nodeId), "0", 1, null).total();
        long filterEntries = summary.isEmpty() ? -1L : redis.scanHash(
                keys.filters(nodeId), "0", 1, null).total();
        return NodeView.of(nodeId, summary, clientEntries, filterEntries,
                properties.nodeStaleAfterMs());
    }

    // ------------------------------------------------------------------
    // 客户端
    // ------------------------------------------------------------------

    /**
     * 某节点的客户端分页。
     *
     * @param nodeId 节点 id
     * @param cursor HSCAN 游标
     * @param count  本页条数
     * @param prefix clientId 前缀过滤(用 MATCH, 由 Redis 侧完成, 不拉全量后过滤)
     */
    public ClientPage clients(String nodeId, String cursor, int count, String prefix) {
        AdminRedis.ScanResult result = redis.scanHash(
                keys.clients(nodeId), cursor, count, prefixPattern(prefix));
        List<ClientEntry> entries = new ArrayList<>(result.entries().size());
        for (Map.Entry<String, String> entry : result.entries().entrySet()) {
            entries.add(new ClientEntry(nodeId, entry.getKey(), parse(entry.getValue())));
        }
        entries.sort(Comparator.comparing(ClientEntry::clientId));
        return new ClientPage(entries, result.cursor(), result.finished(), result.total());
    }

    /**
     * 跨节点客户端视图。
     *
     * <p><b>每节点各取 limit 条后合并</b>, 不是全局前 limit 条 —— 见类注释。
     * 因此返回结果里带着每节点的取数情况, 供前端如实展示。
     */
    public ClusterPage clientsAcrossNodes(int limit, String prefix) {
        List<ClientEntry> merged = new ArrayList<>();
        List<NodeFetch> fetches = new ArrayList<>();
        for (String nodeId : new TreeSet<>(redis.smembers(keys.nodes()))) {
            if (!redis.exists(keys.node(nodeId))) {
                continue;
            }
            AdminRedis.ScanResult result = redis.scanHash(
                    keys.clients(nodeId), "0", limit, prefixPattern(prefix));
            for (Map.Entry<String, String> entry : result.entries().entrySet()) {
                merged.add(new ClientEntry(nodeId, entry.getKey(), parse(entry.getValue())));
            }
            fetches.add(new NodeFetch(nodeId, result.entries().size(), result.total(),
                    result.total() > result.entries().size()));
        }
        merged.sort(Comparator.comparing(ClientEntry::node).thenComparing(ClientEntry::clientId));
        boolean truncated = merged.size() > limit;
        if (truncated) {
            merged = new ArrayList<>(merged.subList(0, limit));
        }
        return new ClusterPage(merged, fetches, limit, truncated);
    }

    public ClientEntry client(String nodeId, String clientId) {
        Map<String, String> value = redis.hgetSome(keys.clients(nodeId), clientId);
        String json = value.get(clientId);
        if (json == null) {
            return null;
        }
        return new ClientEntry(nodeId, clientId, parse(json));
    }

    /**
     * 某个订阅过滤器的全部订阅者 —— 扫描各节点的客户端注册表,
     * 找出 filters 里包含该过滤器的客户端(精确匹配)。
     *
     * <p>这是「点开一个订阅 topic, 看谁在订阅它」的查询。数据来源是 broker 随客户端
     * 状态发布的 filters 列表(有 max-filters-per-client 上限), 因此:
     * <ul>
     *   <li>订阅数超过上限的客户端, 其 filters 被截断 —— 该客户端可能订阅了
     *       该过滤器却没出现在结果里(filtersTruncated 的客户端越少越可信)</li>
     *   <li>返回的订阅者数<b>不一定等于</b>过滤器视图里的人数 —— 后者数的是订阅表,
     *       这里数的是「上报了该过滤器的在线客户端」</li>
     * </ul>
     *
     * @param filter 精确的过滤器串(不是主题名 —— 控制台没有主题→过滤器的反查数据)
     * @param limit  最多返回多少个订阅者(截断时 truncated=true, matched 是已找到数)
     */
    public FilterDetail filterDetail(String filter, int limit) {
        List<ClientEntry> subscribers = new ArrayList<>();
        int scanned = 0;
        boolean truncated = false;
        for (String nodeId : new TreeSet<>(redis.smembers(keys.nodes()))) {
            String cursor = "0";
            int rounds = 0;
            AdminRedis.ScanResult page;
            do {
                page = redis.scanHash(
                        keys.clients(nodeId), cursor, properties.maxScanCount(), null);
                rounds++;
                for (Map.Entry<String, String> entry : page.entries().entrySet()) {
                    scanned++;
                    if (hasFilter(parse(entry.getValue()), filter)) {
                        subscribers.add(new ClientEntry(nodeId, entry.getKey(), parse(entry.getValue())));
                        if (subscribers.size() >= limit) {
                            return new FilterDetail(filter, subscribers, scanned, true);
                        }
                    }
                }
                cursor = page.cursor();
            } while (!page.finished() && rounds < MAX_SCAN_ROUNDS);
            if (rounds >= MAX_SCAN_ROUNDS) {
                truncated = true;
            }
        }
        return new FilterDetail(filter, subscribers, scanned, truncated);
    }

    @SuppressWarnings("unchecked")
    private static boolean hasFilter(Map<String, Object> attributes, String filter) {
        Object filters = attributes.get("filters");
        return filters instanceof List<?> list && list.contains(filter);
    }

    /**
     * @param matched   找到并返回的订阅者数(达到 limit 即停, 结果是下界)
     * @param scanned   实际扫描的客户端字段数 —— 说明「这个结论基于多少数据」
     * @param truncated 是否未扫完(轮数上限或订阅者数上限)
     */
    public record FilterDetail(String filter, List<ClientEntry> subscribers,
                               int scanned, boolean truncated) {
    }

    // ------------------------------------------------------------------
    // 主题(topic 过滤器)
    // ------------------------------------------------------------------

    /**
     * 某节点的主题过滤器分页。
     *
     * @param sortByCount 是否按订阅者数排序。开启时会连续扫描(受 {@link #AGGREGATE_SCAN_LIMIT}
     *                    限制)并排序, 因此<b>结果可能不完整</b> —— 返回值里的
     *                    {@code scanned}/{@code truncated} 会如实说明
     */
    public TopicPage topics(String nodeId, String cursor, int count, String prefix, boolean sortByCount) {
        if (!sortByCount) {
            AdminRedis.ScanResult result = redis.scanHash(
                    keys.filters(nodeId), cursor, count, prefixPattern(prefix));
            return new TopicPage(toTopics(nodeId, counts(result.entries())), result.cursor(),
                    result.finished(), result.total(), result.entries().size(), false);
        }
        // 按订阅者数排序需要看到足够多的数据才谈得上「排序」, 而 HSCAN 每次只给一页 ——
        // 所以这里连续扫到上限为止。它是有代价的, 因此只有在用户显式要求排序时才做。
        Map<String, Integer> collected = new LinkedHashMap<>();
        String scanCursor = "0";
        boolean truncated = false;
        boolean finished = false;
        int rounds = 0;
        while (rounds++ < MAX_SCAN_ROUNDS) {
            AdminRedis.ScanResult page = redis.scanHash(
                    keys.filters(nodeId), scanCursor, properties.maxScanCount(), prefixPattern(prefix));
            collected.putAll(counts(page.entries()));
            scanCursor = page.cursor();
            finished = page.finished();
            if (finished || collected.size() >= AGGREGATE_SCAN_LIMIT) {
                truncated = !finished;
                break;
            }
        }
        List<TopicEntry> sorted = toTopics(nodeId, collected);
        sorted.sort(Comparator.comparingInt(TopicEntry::subscribers).reversed()
                .thenComparing(TopicEntry::topicFilter));
        List<TopicEntry> limited = sorted.size() > count ? new ArrayList<>(sorted.subList(0, count)) : sorted;
        long total = redis.scanHash(keys.filters(nodeId), "0", 1, null).total();
        return new TopicPage(limited, "0", true, total, collected.size(), truncated);
    }

    /**
     * 跨节点的主题视图。订阅者数按节点分别给出(不做求和) ——
     * 「3 个节点各 1 个订阅者」与「1 个节点 3 个订阅者」是完全不同的两件事:
     * 前者意味着消息必须跨节点, 后者不需要。
     */
    public List<TopicEntry> topicsAcrossNodes(int limit, String prefix, boolean sortByCount) {
        Map<String, Map<String, Integer>> byFilter = new HashMap<>();
        for (String nodeId : new TreeSet<>(redis.smembers(keys.nodes()))) {
            if (!redis.exists(keys.node(nodeId))) {
                continue;
            }
            Map<String, String> page = redis.scanHash(
                    keys.filters(nodeId), "0", properties.maxScanCount(), prefixPattern(prefix)).entries();
            for (Map.Entry<String, Integer> entry : counts(page).entrySet()) {
                byFilter.computeIfAbsent(entry.getKey(), k -> new LinkedHashMap<>())
                        .put(nodeId, entry.getValue());
            }
        }
        List<TopicEntry> entries = new ArrayList<>(byFilter.size());
        byFilter.forEach((filter, perNode) -> entries.add(buildTopic(filter, perNode)));
        if (sortByCount) {
            entries.sort(Comparator.comparingInt(TopicEntry::totalSubscribers).reversed()
                    .thenComparing(TopicEntry::topicFilter));
        } else {
            entries.sort(Comparator.comparing(TopicEntry::topicFilter));
        }
        return entries.size() > limit ? new ArrayList<>(entries.subList(0, limit)) : entries;
    }

    // ------------------------------------------------------------------
    // 跨节点订阅重叠检测
    // ------------------------------------------------------------------

    /**
     * 检查「同一个主题过滤器是否在多个节点上都有订阅者」。
     *
     * <h2>这个检查为什么重要</h2>
     * 当集群广播被关掉(或收敛到过滤器白名单)时, 一个主题若只有本节点有订阅者, 一切正常;
     * 但若<b>其他节点也有订阅者</b>, 那些订阅者将永远收不到消息 —— 而且没有任何报错。
     *
     * <p>这正是「关掉广播」这个决定唯一的风险点, 而它恰好是控制台能回答的问题:
     * 每个节点的过滤器视图都在 Redis 上, 把它们对起来看, 重叠就是需要跨节点投递的证据。
     * 于是「关广播」从一个凭信心的决定, 变成一个可以被验证的决定。
     *
     * @param maxFilters 最多返回多少条重叠项
     * @param maxScan    扫描上限(总字段数), 超出即截断
     */
    public OverlapReport overlap(int maxFilters, int maxScan) {
        int scanLimit = Math.min(Math.max(maxScan, 100), AGGREGATE_SCAN_LIMIT);
        // filter -> (node -> 订阅者数)
        Map<String, Map<String, Integer>> byFilter = new HashMap<>();
        List<String> scannedNodes = new ArrayList<>();
        int scanned = 0;
        boolean truncated = false;

        for (String nodeId : new TreeSet<>(redis.smembers(keys.nodes()))) {
            if (!redis.exists(keys.node(nodeId))) {
                continue;
            }
            scannedNodes.add(nodeId);
            String cursor = "0";
            int rounds = 0;
            boolean finished = false;
            while (rounds++ < MAX_SCAN_ROUNDS) {
                AdminRedis.ScanResult page = redis.scanHash(
                        keys.filters(nodeId), cursor, properties.maxScanCount(), null);
                scanned += page.entries().size();
                for (Map.Entry<String, Integer> entry : counts(page.entries()).entrySet()) {
                    byFilter.computeIfAbsent(entry.getKey(), k -> new LinkedHashMap<>())
                            .put(nodeId, entry.getValue());
                }
                cursor = page.cursor();
                finished = page.finished();
                if (finished || scanned >= scanLimit) {
                    truncated = !finished;
                    break;
                }
            }
            if (scanned >= scanLimit) {
                truncated = true;
                break;
            }
        }

        List<TopicEntry> overlapping = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : byFilter.entrySet()) {
            if (entry.getValue().size() > 1) {
                overlapping.add(buildTopic(entry.getKey(), entry.getValue()));
            }
        }
        overlapping.sort(Comparator.comparingInt((TopicEntry t) -> t.nodes().size()).reversed()
                .thenComparing(TopicEntry::topicFilter));
        boolean limited = overlapping.size() > maxFilters;
        if (limited) {
            overlapping = new ArrayList<>(overlapping.subList(0, maxFilters));
        }
        return new OverlapReport(overlapping, scannedNodes, scanned, truncated || limited,
                overlapping.size());
    }

    // ------------------------------------------------------------------

    /**
     * @param counts 主题过滤器 -&gt; 订阅者数(已经解析过, 不是 Redis 上的原始字符串)
     */
    private List<TopicEntry> toTopics(String nodeId, Map<String, Integer> counts) {
        List<TopicEntry> entries = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            Map<String, Integer> perNode = new LinkedHashMap<>();
            perNode.put(nodeId, entry.getValue());
            entries.add(buildTopic(entry.getKey(), perNode));
        }
        entries.sort(Comparator.comparing(TopicEntry::topicFilter));
        return entries;
    }

    private static TopicEntry buildTopic(String filter, Map<String, Integer> perNode) {
        int total = perNode.values().stream().mapToInt(Integer::intValue).sum();
        return new TopicEntry(filter, total, new LinkedHashMap<>(perNode),
                new ArrayList<>(new LinkedHashSet<>(perNode.keySet())));
    }

    private static Map<String, Integer> counts(Map<String, String> raw) {
        Map<String, Integer> counts = new LinkedHashMap<>(raw.size());
        raw.forEach((filter, value) -> {
            try {
                counts.put(filter, Integer.parseInt(value));
            } catch (NumberFormatException e) {
                // 值不是数字说明这条数据坏了, 跳过而不是让它把整个列表带崩
                counts.put(filter, 0);
            }
        });
        return counts;
    }

    private static String prefixPattern(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        // HSCAN 的 MATCH 是 glob: 用户输入的 * ? [ 有特殊含义, 必须转义,
        // 否则「查 client-1」会变成「查所有以 client-1 开头的」这种静默的语义偏移
        String escaped = prefix.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("?", "\\?")
                .replace("[", "\\[")
                .replace("]", "\\]");
        return escaped + "*";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.debug("客户端状态 JSON 解析失败: {}", json, e);
            return Map.of("raw", json);
        }
    }

    // ------------------------------------------------------------------
    // 返回类型
    // ------------------------------------------------------------------

    /**
     * @param attributes broker 上报的原始属性(clientId/addr/ver/keepAlive/connectedAt/subs/…)
     */
    public record ClientEntry(String node, String clientId, Map<String, Object> attributes) {
    }

    /**
     * @param cursor   下一页游标
     * @param finished 是否已扫完
     * @param total    该节点客户端总数; -1 表示读不到
     */
    public record ClientPage(List<ClientEntry> entries, String cursor, boolean finished, long total) {
    }

    /**
     * @param subscribers 订阅者总数(把所有节点相加)
     * @param perNode     每个节点各自的订阅者数。跨节点场景下必须分开看:
     *                    「3 节点各 1」与「1 节点 3」是完全不同的两件事
     * @param nodes       出现该过滤器的节点
     */
    public record TopicEntry(String topicFilter, int subscribers,
                             Map<String, Integer> perNode, List<String> nodes) {

        public int totalSubscribers() {
            return subscribers;
        }
    }

    /**
     * @param scanned   本轮实际扫描的字段数
     * @param truncated 是否因为达到上限而截断(结果不完整)
     * @param returned  返回的重叠项数
     */
    public record OverlapReport(List<TopicEntry> overlapping, List<String> nodes,
                               int scanned, boolean truncated, int returned) {
    }

    /**
     * @param fetched   本节点实际取回条数
     * @param total     本节点总数; -1 表示读不到
     * @param truncated 本节点是否被截断
     */
    public record NodeFetch(String node, int fetched, long total, boolean truncated) {
    }

    /**
     * @param perNodeLimit 合并前每节点取的条数。前端必须展示它, 否则会把结果误当成全局排序
     */
    public record ClusterPage(List<ClientEntry> entries, List<NodeFetch> perNode,
                              int perNodeLimit, boolean truncated) {
    }

    /**
     * @param scanned  本次扫描的条数, 用于说明「排序基于多少数据」
     * @param truncated 是否截断
     */
    public record TopicPage(List<TopicEntry> entries, String cursor, boolean finished,
                            long total, int scanned, boolean truncated) {
    }
}

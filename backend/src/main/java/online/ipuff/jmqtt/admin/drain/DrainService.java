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
package online.ipuff.jmqtt.admin.drain;

import online.ipuff.jmqtt.admin.AdminProperties;
import online.ipuff.jmqtt.admin.command.NodeCommandService;
import online.ipuff.jmqtt.admin.query.ClusterQueryService;
import online.ipuff.jmqtt.admin.query.NodeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 驱逐流程编排。
 *
 * <h2>流程, 以及每一步为什么必须存在</h2>
 * <pre>
 *   ① 建立基线        记录各节点当前连接数 —— 没有基线就无法回答「迁走了多少」
 *   ② 预检            观测一段时间, 看新连接是否还在进来 —— 这是「停调度生效了没有」的唯一客观证据
 *   ③ 人工确认已停调度  负载均衡不受控制台控制, 只能由人操作并确认
 *   ④ 分批驱逐        交给 broker 执行, 控制台只观察进度
 *   ⑤ 等待并校验重连   看目标节点连接数是否下降、其他节点是否上升
 *   ⑥ 结论            完成 / 超时(附具体诊断)
 * </pre>
 *
 * <h2>② 预检为什么不能省</h2>
 * 如果负载均衡仍在向该节点调度新连接, 驱逐就变成一个<b>死循环</b>: 断开的客户端立刻
 * 从同一个入口重连回来, 控制台看到的是「驱逐一直在进行、连接数却始终不降」。
 * 没有预检的话, 这个状态会一直持续到批量跑完, 然后报一个含糊的超时 ——
 * 而真实原因(调度没停)一句话就能说清。
 *
 * <h2>校验为什么必须看「其他节点涨了没有」</h2>
 * 只看目标节点连接数下降是不够的: 客户端可能是被断开了但根本没重连成功(设备离线、
 * 证书过期、DNS 未更新)。那时目标节点确实变空, 但业务其实已经中断 ——
 * <b>「迁走了」和「连不上了」在单节点视角下长得一模一样</b>, 必须靠其他节点的连接数上涨来区分。
 */
@Service
public class DrainService {

    private static final Logger log = LoggerFactory.getLogger(DrainService.class);

    /** 目标节点连接数至少下降计划的这个比例, 才认为驱逐真的生效了 */
    private static final double MIN_DROP_RATIO = 0.9;

    /** 其他节点至少要接住这个比例的客户端, 才认为「迁走了」而不是「连不上了」 */
    private static final double MIN_ELSEWHERE_RATIO = 0.5;

    /** 下降量不足计划的一半 → 客户端很可能回连到了同一节点 */
    private static final double SELF_RECONNECT_THRESHOLD = 0.5;

    private final ClusterQueryService query;
    private final NodeCommandService commands;
    private final AdminProperties properties;

    private final Map<String, DrainSession> sessions = new ConcurrentHashMap<>();

    public DrainService(ClusterQueryService query, NodeCommandService commands,
                        AdminProperties properties) {
        this.query = query;
        this.commands = commands;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 创建一次驱逐。此时只做参数校验与基线, 真正的推进由 {@link #tick()} 负责。
     *
     * @throws IllegalArgumentException 参数不合法或超过驱逐上限
     */
    public DrainSession create(String nodeId, String mode, double value, Integer batchSize,
                               Integer intervalMs, boolean publishWill, String clientIdPrefix) {
        NodeView node = query.node(nodeId);
        if (!node.online()) {
            throw new IllegalArgumentException("节点 " + nodeId + " 当前不在线, 无法驱逐");
        }
        if (!"ratio".equals(mode) && !"count".equals(mode)) {
            throw new IllegalArgumentException("mode 必须是 ratio 或 count");
        }
        if ("ratio".equals(mode) && (value <= 0 || value > 1)) {
            throw new IllegalArgumentException("ratio 必须落在 (0, 1] 区间");
        }
        if ("count".equals(mode) && value < 1) {
            throw new IllegalArgumentException("count 必须 >= 1");
        }

        // 控制台侧先拦一次: 超过上限时给出可执行的建议, 而不是等 broker 那边裁剪成一个
        // 与预期不同的数字 —— 那会让人以为「只驱逐了 5000 条」是正常结果。
        int cap = properties.maxEvictPerTask();
        if ("count".equals(mode) && value > cap) {
            throw new IllegalArgumentException("单次驱逐不能超过 " + cap + " 条(当前 " + (long) value
                    + ")。请分多次执行: 分批慢速驱逐是为了避免遗嘱风暴与重连风暴");
        }
        if ("ratio".equals(mode)) {
            long estimated = (long) Math.ceil(node.connections() * value);
            if (estimated > cap) {
                double suggested = node.connections() <= 0 ? 0
                        : Math.floor(cap * 100.0 / node.connections()) / 100.0;
                throw new IllegalArgumentException("按当前连接数 " + node.connections() + " 计算, "
                        + (int) (value * 100) + "% 约为 " + estimated + " 条, 超过单次上限 " + cap
                        + "。建议比例不超过 " + suggested);
            }
        }

        int batch = batchSize == null || batchSize < 1
                ? properties.defaultEvictBatchSize() : batchSize;
        int interval = intervalMs == null || intervalMs < 1
                ? properties.defaultEvictIntervalMs() : intervalMs;

        String id = UUID.randomUUID().toString();
        DrainSession session = new DrainSession(id, nodeId, mode, value, batch,
                interval, publishWill, clientIdPrefix);
        sessions.put(id, session);
        session.log("已创建驱逐任务: 节点=" + nodeId + " 方式=" + mode + " 值=" + value
                + " 每批=" + batch + " 间隔=" + interval + "ms 发布遗嘱=" + publishWill
                + (clientIdPrefix == null || clientIdPrefix.isBlank() ? "" : " 前缀=" + clientIdPrefix));
        return session;
    }

    public DrainSession get(String id) {
        return sessions.get(id);
    }

    public List<DrainSession> list() {
        List<DrainSession> all = new ArrayList<>(sessions.values());
        all.sort(Comparator.comparingLong(DrainSession::createdAt).reversed());
        return all;
    }

    /**
     * 人工确认「已停止向该节点调度」。
     *
     * @param override 预检发现可疑时是否仍然继续。要求显式传 true ——
     *                 这是唯一一个「需要在知道自己可能做错的情况下继续」的入口,
     *                 不该被一个默认值悄悄放行
     */
    public void confirmLb(String id, boolean override) {
        DrainSession session = require(id);
        if (session.state() != DrainSession.State.PRECHECKED) {
            throw new IllegalStateException("当前状态是 " + session.state() + ", 无法确认停调度"
                    + "(需要先完成预检)");
        }
        if (session.precheckSuspect() && !override) {
            long growth = session.precheckEndCount() - session.precheckStartCount();
            throw new IllegalStateException("预检发现该节点仍在接收新连接(观测期内 +" + growth
                    + " 条), 停止调度可能尚未生效。若确认这是正常的重连流量, 可勾选「忽略预检告警」继续");
        }
        session.transition(DrainSession.State.LB_CONFIRMED,
                "已确认停止向该节点调度" + (session.precheckSuspect() ? "(忽略了预检告警)" : ""));
        log.info("驱逐 [{}] 已确认停调度: node={}", id, session.node());
    }

    /**
     * 开始驱逐。
     */
    public void start(String id) {
        DrainSession session = require(id);
        if (session.state() != DrainSession.State.LB_CONFIRMED) {
            throw new IllegalStateException("当前状态是 " + session.state()
                    + ", 无法开始驱逐(需要先确认已停止调度)");
        }
        Map<String, Long> baseline = query.connectionCounts();
        Long target = baseline.get(session.node());
        if (target == null) {
            throw new IllegalStateException("读不到节点 " + session.node() + " 的连接数, 无法建立基线");
        }
        session.setBaseline(baseline);
        session.setLatestCounts(baseline);
        session.markStarted(System.currentTimeMillis());

        String commandId = commands.evict(session.node(), session.mode(), session.value(),
                session.batchSize(), session.intervalMs(), session.publishWill(),
                session.clientIdPrefix());
        if (commandId == null) {
            session.finish(DrainSession.State.FAILED, "命令下发失败: Redis 不可达");
            return;
        }
        long planned = "count".equals(session.mode())
                ? (long) session.value()
                : (long) Math.ceil(target * session.value());
        session.setEvictCommand(commandId, (int) Math.min(planned, Integer.MAX_VALUE));
        session.setReconnectDeadline(System.currentTimeMillis()
                + properties.reconnectTimeoutSeconds() * 1000L);
        session.transition(DrainSession.State.EVICTING,
                "已下发驱逐命令: 计划约 " + planned + " 条, 基线连接数 " + target);
    }

    public void abort(String id) {
        DrainSession session = require(id);
        if (session.state().terminal()) {
            throw new IllegalStateException("任务已处于终态 " + session.state());
        }
        session.requestAbort();
        if (session.evictCommandId() != null) {
            commands.abortEviction(session.node(), session.evictCommandId());
        }
        session.finish(DrainSession.State.ABORTED, "已人工中止");
        log.info("驱逐 [{}] 已中止", id);
    }

    // ------------------------------------------------------------------
    // 推进
    // ------------------------------------------------------------------

    /**
     * 推进所有非终态会话。
     *
     * <p>单线程(Spring 的调度线程)依次推进, 因此这里不需要对会话加锁 ——
     * 会话内部的字段用 volatile 保护, 而「读-判断-写」这段逻辑只有这一个线程在跑。
     * API 线程只做「创建 / 确认 / 开始 / 中止」这些幂等的状态置位。
     */
    @Scheduled(fixedDelayString = "${jmqtt.admin.reconnect-poll-interval-ms:2000}")
    public void tick() {
        for (DrainSession session : sessions.values()) {
            try {
                advance(session);
            } catch (Exception e) {
                log.error("推进驱逐任务失败: id={} state={}", session.id(), session.state(), e);
                session.finish(DrainSession.State.FAILED, "推进出错: " + e);
            }
        }
    }

    private void advance(DrainSession session) {
        if (session.state().terminal()) {
            return;
        }
        if (session.abortRequested()) {
            session.finish(DrainSession.State.ABORTED, "已人工中止");
            return;
        }
        switch (session.state()) {
            case CREATED -> beginPrecheck(session);
            case PRECHECKING -> finishPrecheck(session);
            case EVICTING -> pollEviction(session);
            case WAITING_RECONNECT -> verifyReconnect(session);
            default -> {
                // PRECHECKED / LB_CONFIRMED 是「等人操作」的状态, 不需要推进
            }
        }
    }

    private void beginPrecheck(DrainSession session) {
        Map<String, Long> counts = query.connectionCounts();
        Long current = counts.get(session.node());
        if (current == null) {
            session.finish(DrainSession.State.FAILED, "读不到节点连接数, 无法预检");
            return;
        }
        session.setLatestCounts(counts);
        session.beginPrecheck(System.currentTimeMillis(), current);
        session.transition(DrainSession.State.PRECHECKING,
                "开始预检: 观测 " + properties.precheckWindowMs() + "ms 内是否仍有新连接进入"
                        + "(当前连接数 " + current + ")");
    }

    private void finishPrecheck(DrainSession session) {
        long elapsed = System.currentTimeMillis() - session.precheckStartAt();
        if (elapsed < properties.precheckWindowMs()) {
            return;
        }
        Map<String, Long> counts = query.connectionCounts();
        Long current = counts.get(session.node());
        if (current == null) {
            session.finish(DrainSession.State.FAILED, "预检期间节点失联");
            return;
        }
        session.setLatestCounts(counts);
        long growth = current - session.precheckStartCount();
        boolean suspect = growth > properties.precheckGrowthThreshold();
        session.endPrecheck(current, suspect);
        if (suspect) {
            session.transition(DrainSession.State.PRECHECKED,
                    "预检: 观测期内新增 " + growth + " 条连接, 超过阈值 "
                            + properties.precheckGrowthThreshold()
                            + " 条 —— 停止向该节点调度可能尚未生效。请确认负载均衡配置后再继续");
        } else {
            session.transition(DrainSession.State.PRECHECKED,
                    "预检通过: 观测期内连接数变化 " + growth + " 条, 未见持续新连接进入");
        }
    }

    private void pollEviction(DrainSession session) {
        Map<String, String> result = commands.result(session.evictCommandId());
        if (result == null) {
            long waited = System.currentTimeMillis() - session.startedAt();
            if (waited > 30_000) {
                session.finish(DrainSession.State.FAILED,
                        "节点在 30s 内没有响应驱逐命令(" + session.node()
                                + ")。可能原因: 该节点进程已停止、管理面未启用、或命令队列被占满");
            }
            return;
        }
        String state = result.getOrDefault("state", "");
        session.setLatestCounts(query.connectionCounts());
        switch (state) {
            case "RUNNING" -> {
                int evicted = parseInt(result.get("evicted"), 0);
                int target = parseInt(result.get("target"), 0);
                session.setEvictOutcome(evicted, "驱逐进行中: " + evicted + "/" + target);
                session.log("驱逐进行中: " + evicted + "/" + target
                        + " 本节点连接数 " + result.getOrDefault("nodeConnections", "?"));
            }
            case "COMPLETED" -> {
                int evicted = parseInt(result.get("evicted"), 0);
                session.setEvictOutcome(evicted, "驱逐完成: 已断开 " + evicted + " 条");
                session.setLatestCounts(query.connectionCounts());
                session.transition(DrainSession.State.WAITING_RECONNECT,
                        "驱逐已完成(" + evicted + " 条), 开始等待客户端重连到其他节点, 限时 "
                                + properties.reconnectTimeoutSeconds() + "s");
            }
            case "ABORTED" -> session.finish(DrainSession.State.ABORTED,
                    "节点侧报告已中止: " + result.getOrDefault("message", ""));
            case "RECEIVED" -> {
                // 已受理但还没开始跑, 继续等
            }
            default -> session.finish(DrainSession.State.FAILED,
                    "节点拒绝执行: state=" + state + " " + result.getOrDefault("message", ""));
        }
    }

    private void verifyReconnect(DrainSession session) {
        Map<String, Long> counts = query.connectionCounts();
        session.setLatestCounts(counts);

        long drop = session.observedDrop();
        long elsewhere = session.observedElsewhereGain();
        int planned = Math.max(session.evictTarget(), session.evicted());
        long now = System.currentTimeMillis();

        if (drop < 0) {
            if (now > session.reconnectDeadline()) {
                session.finish(DrainSession.State.TIMEOUT, "读不到节点连接数, 无法校验(节点失联?)");
            }
            return;
        }

        boolean enoughDrop = planned <= 0 || drop >= planned * MIN_DROP_RATIO;
        boolean enoughElsewhere = planned <= 0 || elsewhere >= planned * MIN_ELSEWHERE_RATIO;

        if (enoughDrop && enoughElsewhere) {
            session.finish(DrainSession.State.COMPLETED,
                    "驱逐完成: 目标节点减少 " + drop + " 条, 其他节点合计增加 " + elsewhere
                            + " 条(计划 " + planned + " 条)");
            log.info("驱逐 [{}] 完成: node={} drop={} elsewhere={}",
                    session.id(), session.node(), drop, elsewhere);
            return;
        }

        // 提前判定「回连同节点」: 不必等到超时, 这个结论越早给出越好 ——
        // 操作者可以立刻去查负载均衡, 而不是在那里干等三分钟
        if (planned > 0 && drop < planned * SELF_RECONNECT_THRESHOLD
                && now > session.startedAt() + 15_000) {
            session.finish(DrainSession.State.TIMEOUT,
                    "疑似回连同一节点: 计划驱逐 " + planned + " 条, 但目标节点连接数只减少了 "
                            + drop + " 条(其他节点合计增加 " + elsewhere + " 条)。"
                            + "最可能的原因是负载均衡仍在向该节点调度 —— 请确认停止调度已真正生效");
            return;
        }

        if (now > session.reconnectDeadline()) {
            session.finish(DrainSession.State.TIMEOUT, diagnose(session, drop, elsewhere, planned));
        }
    }

    /**
     * 超时诊断。
     *
     * <p>把「目标节点降了没有」与「其他节点涨了没有」两个维度组合起来, 就能区分
     * 三种完全不同的问题: 没执行、回连同节点、没重连上。用一段含糊的「超时」把它们
     * 混在一起, 等于让操作者从头查一遍。
     */
    private String diagnose(DrainSession session, long drop, long elsewhere, int planned) {
        String node = session.node();
        if (drop <= 0) {
            return "超时: 目标节点 " + node + " 的连接数没有下降(基线 "
                    + session.baseline().getOrDefault(node, -1L) + ", 现在 "
                    + session.latestCounts().getOrDefault(node, -1L) + ", 计划驱逐 " + planned
                    + " 条)。最可能的原因是该节点没有执行驱逐命令 —— 请检查节点日志中"
                    + "「开始驱逐」是否出现, 以及管理面是否已启用";
        }
        if (drop < planned * MIN_DROP_RATIO) {
            return "超时: 目标节点只减少了 " + drop + " 条(计划 " + planned
                    + " 条), 且其他节点合计增加 " + elsewhere + " 条。"
                    + "差额部分很可能是客户端回连到了同一节点 —— 请确认停止调度已生效";
        }
        return "超时: 目标节点已减少 " + drop + " 条, 但其他节点合计只增加 " + elsewhere
                + " 条。客户端可能尚未完成重连, 或重连到了未启用管理面的节点。"
                + "请检查设备的重连日志与其他节点的连接数曲线";
    }

    private DrainSession require(String id) {
        DrainSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("找不到驱逐任务: " + id);
        }
        return session;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

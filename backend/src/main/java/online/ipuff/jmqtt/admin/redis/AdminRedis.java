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
 */
package online.ipuff.jmqtt.admin.redis;

import io.lettuce.core.KeyValue;
import io.lettuce.core.MapScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import online.ipuff.jmqtt.admin.AdminProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 控制台与 Redis 之间的唯一通道。
 *
 * <h2>与 broker 的降级策略相反, 这是刻意的</h2>
 * broker 遇到 Redis 故障会<b>静默降级</b>(会话退化为新会话、镜像不落盘), 因为它的首要目标是
 * 「接入必须一直可用」。控制台正相反: 它唯一的数据来源就是 Redis, 连不上时<b>必须明说</b>。
 *
 * <p>这不是洁癖。想想「客户端列表为空」这个结果: 它可能是「集群真的没有客户端」,
 * 也可能是「Redis 连不上」。前者是正常状态, 后者是故障 —— 若把后者显示成前者,
 * 操作者会基于一个完全错误的判断去执行驱逐。所以这里维护一个显式的可达状态,
 * 由 {@code /api/overview} 一并返回, 前端在连不上时显示横幅而不是一个空列表。
 *
 * <h2>为什么用单连接而不是连接池</h2>
 * 控制台是人工操作频率(每秒几次查询), 单连接多路复用足够。池化在这里只会引入
 * 额外的连接管理成本, 以及「连接被占满」这种在控制台上毫无意义的失败模式。
 */
@Component
public class AdminRedis {

    private static final Logger log = LoggerFactory.getLogger(AdminRedis.class);

    private final AdminRedisSource source;
    private final AdminKeys keys;
    private final int maxScanCount;

    private volatile AdminRedisSource.Handle connection;
    private volatile Status status = new Status(false, "尚未连接", 0L);

    /**
     * 可达状态。
     *
     * @param reachable 最近一次探测是否成功
     * @param error     最近一次失败原因; 可达时为 null
     * @param checkedAt 最近一次探测时间
     */
    public record Status(boolean reachable, String error, long checkedAt) {
    }

    /**
     * 一页 HSCAN 结果。
     *
     * @param entries  本页数据
     * @param cursor   下一页游标
     * @param finished 是否已扫完
     * @param total    该 hash 的字段总数; -1 表示读不到(Redis 故障), 与「0 个字段」必须区分
     */
    public record ScanResult(Map<String, String> entries, String cursor, boolean finished, long total) {
    }

    public AdminRedis(AdminRedisSource source, AdminProperties properties, AdminKeys keys) {
        this.source = source;
        this.keys = keys;
        this.maxScanCount = properties.maxScanCount();
    }

    public Status status() {
        return status;
    }

    /**
     * 周期性探活。控制台不做静默重试, 因此这个探测是「Redis 回来了」的唯一发现途径 ——
     * 它必须比任何业务查询都先察觉到。
     */
    @Scheduled(fixedDelayString = "${jmqtt.admin.redis-health-interval-ms:5000}")
    public void healthCheck() {
        try {
            commands().ping();
            markReachable();
        } catch (Exception e) {
            markUnreachable(e);
        }
    }

    /**
     * 统一的命令包装: 成功刷新可达状态, 失败标记故障并返回兜底值。
     */
    public <T> T execute(String op, Supplier<T> action, T fallback) {
        try {
            T result = action.get();
            markReachable();
            return result;
        } catch (Exception e) {
            markUnreachable(e);
            log.debug("Redis 命令失败 op={}", op, e);
            return fallback;
        }
    }

    public AdminCommands commands() {
        return connection().commands();
    }

    // ------------------------------------------------------------------
    // 控制台需要的几个命令
    // ------------------------------------------------------------------

    public Map<String, String> hgetAll(String key) {
        return execute("hgetall " + key, () -> commands().hgetall(key), Map.of());
    }

    /**
     * 只取指定字段。
     *
     * <p>用于判断节点是否活着的场景: 节点概要 hash 有几十个字段, 而判活只看
     * {@code updatedAt} 一个 —— HGETALL 会把整个概要读回来, 在节点很多时是纯浪费。
     */
    public Map<String, String> hgetSome(String key, String... fields) {
        if (fields.length == 0) {
            return Map.of();
        }
        return execute("hmget " + key, () -> {
            List<KeyValue<String, String>> values = commands().hmget(key, fields);
            Map<String, String> result = new LinkedHashMap<>();
            for (KeyValue<String, String> kv : values) {
                if (kv.hasValue()) {
                    result.put(kv.getKey(), kv.getValue());
                }
            }
            return result;
        }, Map.of());
    }

    public List<String> lrange(String key, long start, long stop) {
        return execute("lrange " + key, () -> commands().lrange(key, start, stop), List.of());
    }

    public String lindex(String key, long index) {
        return execute("lindex " + key, () -> commands().lindex(key, index), null);
    }

    /**
     * LPUSH + LTRIM: 头插并截断到上限, 用于「新的在左」的有界序列(指标历史帧)。
     */
    public void listPushTruncate(String key, String value, int maxLength) {
        execute("lpush " + key, () -> {
            commands().lpush(key, value);
            commands().ltrim(key, 0, maxLength - 1L);
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }

    public void expire(String key, long seconds) {
        execute("expire " + key, () -> commands().expire(key, seconds), Boolean.FALSE);
    }

    public boolean exists(String key) {
        return Boolean.TRUE.equals(execute("exists " + key,
                () -> commands().exists(key) > 0, Boolean.FALSE));
    }

    public Set<String> smembers(String key) {
        return execute("smembers " + key, () -> commands().smembers(key), Set.of());
    }

    public boolean srem(String key, String member) {
        Long removed = execute("srem " + key, () -> commands().srem(key, member), 0L);
        return removed != null && removed > 0;
    }

    public long del(String... keys) {
        Long removed = execute("del", () -> commands().del(keys), 0L);
        return removed == null ? 0L : removed;
    }

    /**
     * 游标式扫描一个 hash。
     *
     * <p>用 HSCAN 而不是 HGETALL: 十万级连接下一个节点的客户端注册表可能有十几万字段,
     * 全量读出来在控制台是几十 MB 的传输。HSCAN 每次只取一页, 代价与「取多少」成正比,
     * 而不是与「总共有多少」成正比。
     *
     * @param cursor 上一页返回的游标; 起始传 "0"
     * @param count  本页期望条数(HSCAN 的 COUNT 是提示, 实际可能更多)
     * @param match  可选的 MATCH 模式, 例如 {@code "node-a-*"}; null 或空表示不过滤
     */
    public ScanResult scanHash(String key, String cursor, int count, String match) {
        int limit = Math.max(1, Math.min(count, maxScanCount));
        // 注意 Lettuce 6.1 的 API 形状: ScanArgs.Builder 上只有静态工厂方法,
        // 实例式的 limit/match 在 ScanArgs 自己身上(返回 this, 可链式)。
        // 写成 ScanArgs.Builder.limit(n).match(...) 会编译不过 —— 前者返回的是 ScanArgs,
        // 而编译器会把它当成 Builder 的静态方法解析
        ScanArgs args = new ScanArgs().limit(limit);
        if (match != null && !match.isEmpty()) {
            args.match(match);
        }
        Long total = countOrNull(key);
        return execute("hscan " + key, () -> {
            MapScanCursor<String, String> result =
                    commands().hscan(key, ScanCursor.of(cursor == null ? "0" : cursor), args);
            return new ScanResult(result.getMap(), result.getCursor(), result.isFinished(),
                    total == null ? -1L : total);
        }, new ScanResult(Map.of(), "0", true, total == null ? -1L : total));
    }

    /**
     * 往命令队列里推一条命令。
     *
     * <p><b>写入后必须 LTRIM。</b>否则控制台若连续下发(例如按钮被连点), 队列会无限增长,
     * 而节点一次只取一条 —— 积压的命令里有一部分必然已失去时效, 到时会被逐条取出并
     * 判定为过期, 白白占用节点的轮询周期。
     */
    public boolean pushCommand(String nodeId, String commandJson, int maxQueueLength) {
        String queue = keys.commandQueue(nodeId);
        return Boolean.TRUE.equals(execute("lpush " + queue, () -> {
            commands().lpush(queue, commandJson);
            commands().ltrim(queue, 0, maxQueueLength - 1L);
            return Boolean.TRUE;
        }, Boolean.FALSE));
    }

    private Long countOrNull(String key) {
        try {
            return commands().hlen(key);
        } catch (Exception e) {
            markUnreachable(e);
            return null;
        }
    }

    private void markReachable() {
        if (!status.reachable()) {
            log.info("Redis 已连接");
        }
        status = new Status(true, null, System.currentTimeMillis());
    }

    private void markUnreachable(Exception e) {
        Status current = status;
        String error = e.getClass().getSimpleName() + ": " + e.getMessage();
        status = new Status(false, error, System.currentTimeMillis());
        // 只在状态从「可用」变「不可用」或错误内容变化时打日志, 否则一次持续的故障会刷满日志
        if (current.reachable() || !error.equals(current.error())) {
            log.error("Redis 不可达, 控制台无法读取任何数据: {}", error);
        }
    }

    /**
     * 惰性连接。与 broker 一致: <b>不在启动时连接</b> ——
     * Redis 挂掉不应该导致控制台起不来(它至少还能把「连不上」这个事实展示出来)。
     */
    private AdminRedisSource.Handle connection() {
        AdminRedisSource.Handle current = connection;
        if (current != null && current.connection().isOpen()) {
            return current;
        }
        synchronized (this) {
            if (connection != null && connection.connection().isOpen()) {
                return connection;
            }
            connection = source.open();
            return connection;
        }
    }
}

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
package online.ipuff.jmqtt.admin.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.ipuff.jmqtt.admin.redis.AdminKeys;
import online.ipuff.jmqtt.admin.redis.AdminRedis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点指标历史的 Redis 存储 —— 分 / 时 / 天 三级粒度。
 *
 * <h2>为什么分三级</h2>
 * 原始帧是 30s 一帧, 存 30 天要两百多万帧, 读取和存储都不可接受。而看图表的人
 * 需要的分辨率取决于跨度: 看 1 小时才需要分钟级, 看一个月只需要天级。
 * 于是写入时同步维护三条有界序列:
 * <ul>
 *   <li><b>分钟</b>(原始帧): 短保留, 服务「跨度 ≤ 1 小时」的细看</li>
 *   <li><b>小时</b>: 跨整点时写一帧, 保留 30 天</li>
 *   <li><b>天</b>: 跨整天时写一帧, 保留 30 天 —— 全局上限</li>
 * </ul>
 * 汇总帧取<b>跨越时刻的最新累计值</b>: 计数器是单调的, 取末值保证任意两级之间
 * 仍然可以差分; 仪表量(连接数等)取末值即「区间末状态」。不做平均 —— 平均需要
 * 缓存桶内全部帧, 换来的平滑对排查没有额外价值。
 *
 * <h2>跨桶判定不依赖内存</h2>
 * 每次写入对比「桶序列里最新一帧的桶」与「当前桶」: 落后才追加。判定信息在 Redis
 * 里而不是内存里, 控制台重启不会造成重复帧或漏帧。
 */
@Service
public class NodeMetricsStore {

    private static final Logger log = LoggerFactory.getLogger(NodeMetricsStore.class);

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final long HOUR_MS = 3600000L;
    private static final long DAY_MS = 86400000L;

    /** 粒度: 分钟(原始)/小时/天 */
    public enum Granularity {
        MINUTE("minute", "分钟", 60000L),
        HOUR("hour", "小时", HOUR_MS),
        DAY("day", "天", DAY_MS);

        public final String id;
        public final String label;
        public final long intervalMs;

        Granularity(String id, String label, long intervalMs) {
            this.id = id;
            this.label = label;
            this.intervalMs = intervalMs;
        }
    }

    private final AdminRedis redis;
    private final AdminKeys keys;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long minuteMaxSamples;
    private final long minuteTtlSeconds;
    private final long hourMaxSamples;
    private final long hourTtlSeconds;
    private final long dayMaxSamples;
    private final long dayTtlSeconds;

    public NodeMetricsStore(AdminRedis redis,
                            AdminKeys keys,
                            @Value("${jmqtt.admin.metrics.minute-max-samples:1440}") long minuteMaxSamples,
                            @Value("${jmqtt.admin.metrics.minute-ttl-seconds:172800}") long minuteTtlSeconds,
                            @Value("${jmqtt.admin.metrics.hour-max-samples:720}") long hourMaxSamples,
                            @Value("${jmqtt.admin.metrics.hour-ttl-seconds:2678400}") long hourTtlSeconds,
                            @Value("${jmqtt.admin.metrics.day-max-samples:31}") long dayMaxSamples,
                            @Value("${jmqtt.admin.metrics.day-ttl-seconds:2678400}") long dayTtlSeconds) {
        this.redis = redis;
        this.keys = keys;
        this.minuteMaxSamples = Math.max(2, minuteMaxSamples);
        this.minuteTtlSeconds = minuteTtlSeconds;
        this.hourMaxSamples = Math.max(2, hourMaxSamples);
        this.hourTtlSeconds = hourTtlSeconds;
        this.dayMaxSamples = Math.max(2, dayMaxSamples);
        this.dayTtlSeconds = dayTtlSeconds;
    }

    /**
     * 追加一帧原始值, 并在跨小时/跨天时追加对应粒度的汇总帧。
     */
    public void append(String nodeId, long ts, Map<String, Object> values) {
        writeFrame(keys.metrics(nodeId), ts, values, minuteMaxSamples, minuteTtlSeconds);
        rollup(keys.metricsHour(nodeId), Granularity.HOUR, ts, values, hourMaxSamples, hourTtlSeconds);
        rollup(keys.metricsDay(nodeId), Granularity.DAY, ts, values, dayMaxSamples, dayTtlSeconds);
    }

    /** 桶序列最新一帧落后于当前桶时, 追加一帧汇总 */
    private void rollup(String key, Granularity granularity, long ts,
                        Map<String, Object> values, long max, long ttl) {
        long bucket = bucketOf(granularity, ts);
        String newest = redis.lindex(key, 0);
        if (newest != null) {
            try {
                long newestBucket = bucketOf(granularity, objectMapper.readTree(newest).path("ts").asLong());
                if (newestBucket >= bucket) {
                    return;
                }
            } catch (Exception e) {
                // 坏帧按「无最新帧」处理, 重写一帧自愈
                log.warn("汇总帧解析失败, 将重写: key={}", key);
            }
        }
        writeFrame(key, ts, values, max, ttl);
    }

    private void writeFrame(String key, long ts, Map<String, Object> values, long max, long ttl) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("ts", ts, "values", values));
            redis.listPushTruncate(key, json, (int) Math.min(max, Integer.MAX_VALUE));
            redis.expire(key, ttl);
        } catch (Exception e) {
            // 观测数据写失败只影响这一帧, 记一条日志即可
            log.warn("指标帧写入失败: key={}", key, e);
        }
    }

    private long bucketOf(Granularity granularity, long ts) {
        return switch (granularity) {
            case MINUTE -> Math.floorDiv(ts, 60000L);
            case HOUR -> Math.floorDiv(ts, HOUR_MS);
            case DAY -> LocalDate.ofInstant(Instant.ofEpochMilli(ts), ZONE).toEpochDay();
        };
    }

    /**
     * 按窗口读取, 粒度由跨度决定: ≤1 小时分钟, ≤24 小时小时, 更长按天。
     * 细粒度在该窗口内不足两帧时自动落到更粗一级(帧太少画不出趋势)。
     *
     * @return {granularity, granularityLabel, intervalMs, samples(oldest → newest)}
     */
    public Map<String, Object> read(String nodeId, long from, long to) {
        long span = Math.max(0, to - from);
        Granularity g = span <= HOUR_MS ? Granularity.MINUTE
                : (span <= DAY_MS ? Granularity.HOUR : Granularity.DAY);
        List<Map<String, Object>> frames = readFrames(nodeId, g, from, to);
        if (frames.size() < 2) {
            Granularity coarser = g == Granularity.MINUTE ? Granularity.HOUR
                    : (g == Granularity.HOUR ? Granularity.DAY : null);
            if (coarser != null) {
                List<Map<String, Object>> coarserFrames = readFrames(nodeId, coarser, from, to);
                if (coarserFrames.size() > frames.size()) {
                    g = coarser;
                    frames = coarserFrames;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node", nodeId);
        result.put("granularity", g.id);
        result.put("granularityLabel", g.label);
        result.put("intervalMs", g.intervalMs);
        result.put("samples", frames);
        return result;
    }

    /** 读指定粒度在窗口内的帧, oldest → newest */
    private List<Map<String, Object>> readFrames(String nodeId, Granularity g, long from, long to) {
        String key = switch (g) {
            case MINUTE -> keys.metrics(nodeId);
            case HOUR -> keys.metricsHour(nodeId);
            case DAY -> keys.metricsDay(nodeId);
        };
        List<String> raw = redis.lrange(key, 0, -1);
        List<Map<String, Object>> frames = new ArrayList<>(raw.size());
        for (String json : raw) {
            try {
                Map<String, Object> frame = objectMapper.readValue(json, Map.class);
                long ts = ((Number) frame.get("ts")).longValue();
                if (ts < from || ts > to) {
                    continue;
                }
                Map<String, Object> ordered = new LinkedHashMap<>();
                ordered.put("ts", ts);
                ordered.put("values", frame.get("values"));
                frames.add(ordered);
            } catch (Exception e) {
                log.warn("指标帧解析失败, 跳过: key={}", key);
            }
        }
        Collections.reverse(frames);
        return frames;
    }
}

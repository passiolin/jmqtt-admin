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
import online.ipuff.jmqtt.admin.command.NodeCommandService;
import online.ipuff.jmqtt.admin.query.ClusterQueryService;
import online.ipuff.jmqtt.admin.query.NodeView;
import online.ipuff.jmqtt.admin.redis.AdminKeys;
import online.ipuff.jmqtt.admin.redis.AdminRedis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点指标采集器: 定时向每个在线节点下发 METRICS 命令, 把返回的当前值存成 Redis 历史帧。
 *
 * <h2>命令是异步的, 采集器怎么等结果</h2>
 * 命令经 Redis 队列到节点、结果写回结果 hash, 全程异步 —— 采集线程<b>绝不阻塞等待</b>。
 * 做法是两段式: 每个采集 tick 先收割上一轮已完成的命令结果(写帧后清理),
 * 再为所有在线节点下发新一轮命令, 在途命令记在内存 pending 表里。
 * 超过 {@code result-timeout-ms} 还没回来的(节点恰好掉线)直接丢弃 ——
 * 指标缺一帧无关痛痒, 为它引入重试状态机不值得。
 *
 * <h2>量级控制</h2>
 * 每个节点每 tick 恰好一条命令, 命令队列本身有长度上限;
 * pending 表有硬上限, 即使节点清单异常膨胀也不会把命令通道灌爆。
 */
@Component
public class NodeMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(NodeMetricsCollector.class);

    /** 在途命令上限: 正常等于在线节点数, 只有异常情况才会逼近这个值 */
    private static final int MAX_PENDING = 64;

    private final ClusterQueryService query;
    private final NodeCommandService commands;
    private final AdminRedis redis;
    private final AdminKeys keys;
    private final NodeMetricsStore store;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long resultTimeoutMs;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(String node, long sentAt) {
    }

    public NodeMetricsCollector(ClusterQueryService query,
                                NodeCommandService commands,
                                AdminRedis redis,
                                AdminKeys keys,
                                NodeMetricsStore store,
                                @Value("${jmqtt.admin.metrics.result-timeout-ms:15000}") long resultTimeoutMs) {
        this.query = query;
        this.commands = commands;
        this.redis = redis;
        this.keys = keys;
        this.store = store;
        this.resultTimeoutMs = resultTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${jmqtt.admin.metrics.collect-interval-ms:30000}")
    public void collect() {
        try {
            harvest();
        } catch (Exception e) {
            // 收割异常不能影响本轮下发, 更不能停掉采集
            log.warn("指标收割失败", e);
        }
        try {
            dispatch();
        } catch (Exception e) {
            log.warn("指标采集下发失败", e);
        }
    }

    /** 收割上一轮在途命令的结果: COMPLETED 写帧, 其他状态或超时丢弃 */
    private void harvest() {
        pending.forEach((commandId, p) -> {
            Map<String, String> result = commands.result(commandId);
            if (result == null) {
                if (System.currentTimeMillis() - p.sentAt() > resultTimeoutMs) {
                    pending.remove(commandId);
                    log.debug("节点指标查询超时, 丢弃本帧: node={}", p.node());
                }
                return;
            }
            pending.remove(commandId);
            redis.del(keys.commandResult(commandId));
            if (!"COMPLETED".equals(result.get("state"))) {
                return;
            }
            try {
                Map<String, Object> snapshot = objectMapper.readValue(result.get("snapshot"), Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> values = (Map<String, Object>) snapshot.get("values");
                if (values != null && !values.isEmpty()) {
                    store.append(p.node(), System.currentTimeMillis(), values);
                }
            } catch (Exception e) {
                log.warn("节点指标快照解析失败: node={}", p.node(), e);
            }
        });
    }

    /** 为所有在线节点下发新一轮采集命令 */
    private void dispatch() {
        if (pending.size() >= MAX_PENDING) {
            log.warn("在途指标命令已达上限 {}, 本轮跳过下发", MAX_PENDING);
            return;
        }
        for (NodeView node : query.nodes()) {
            if (!node.online()) {
                continue;
            }
            String commandId = commands.nodeMetrics(node.node());
            if (commandId != null) {
                pending.put(commandId, new Pending(node.node(), System.currentTimeMillis()));
            }
        }
    }
}

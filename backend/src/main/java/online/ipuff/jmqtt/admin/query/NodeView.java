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
package online.ipuff.jmqtt.admin.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一个节点的视图。
 *
 * <h2>为什么既给结构化字段又给原始 summary</h2>
 * 结构化字段是为了前端不必知道键名就能画出主表; 原始 summary 是为了<b>新增指标不需要改两处</b> ——
 * broker 加了什么字段, 控制台的详情面板立刻就能展示出来, 不必等控制台发版。
 *
 * <p>代价是响应体偏大。对一个人工查看的页面完全可以接受, 而被省略掉的恰恰是
 * 「加一个指标要动三个工程」这种长期摩擦。
 *
 * <h2>warnings 为什么由后端算</h2>
 * 判断「这个数字是不是异常」需要知道它在整个架构里的位置(例如
 * {@code offlineMessagesDropped} 非零意味着 QoS 保证已经破损), 这是后端才有的上下文。
 * 让前端各自去判断, 迟早会出现两处口径不一致, 而运维看到矛盾结论时无从判断谁对。
 *
 * @param node               节点 id
 * @param online             是否在线(心跳新鲜)
 * @param ageMs              距离最近一次心跳的毫秒数
 * @param connections        已注册的 MQTT 连接数
 * @param rawConnections     TCP 层连接数。与 connections 的差值 = 建立了 TCP 但未完成 CONNECT 的连接
 * @param sessions           会话数(含离线保留的持久会话)
 * @param subscriptions      订阅关系总数
 * @param topicNodes         主题树节点数, 用于观测剪枝是否正常
 * @param retainMessages     保留消息数
 * @param clientEntries      Redis 客户端注册表条目数; -1 表示读不到
 * @param filterEntries      Redis 过滤器视图条目数(去重后的主题过滤器数); -1 表示读不到
 * @param broadcastMode      集群广播形态: all / filtered / off / cluster-disabled
 * @param summary            broker 上报的原始字段
 * @param warnings           需要人关注的点
 */
public record NodeView(
        String node,
        boolean online,
        long ageMs,
        long connections,
        long rawConnections,
        long sessions,
        long subscriptions,
        long topicNodes,
        long retainMessages,
        long clientEntries,
        long filterEntries,
        String broadcastMode,
        boolean clusterEnabled,
        boolean clusterBusEnabled,
        boolean uplinkEnabled,
        long startupAt,
        long updatedAt,
        Map<String, String> summary,
        List<String> warnings
) {

    /**
     * 从 Redis 上的概要 hash 组装。
     *
     * @param clientEntries Redis 客户端注册表的实际条目数; -1 表示读不到
     * @param filterEntries Redis 过滤器视图的实际条目数; -1 表示读不到
     * @param staleAfterMs  心跳超过多久视为离线
     */
    public static NodeView of(String nodeId, Map<String, String> summary,
                              long clientEntries, long filterEntries, long staleAfterMs) {
        if (summary == null || summary.isEmpty()) {
            // 概要键不存在 == 该节点的状态已过期。注意这**不等于**「节点从没存在过」:
            // 节点 id 仍留在 nodes 集合里, 因此这里要如实回答「离线」而不是「不存在」。
            return new NodeView(nodeId, false, -1, -1, -1, -1, -1, -1, -1,
                    clientEntries, filterEntries, "unknown", false, false, false, 0, 0,
                    Map.of(), List.of("节点状态已过期: 可能是进程已停止, 或与 Redis 断开"));
        }
        long updatedAt = parseLong(summary.get("updatedAt"), 0L);
        long ageMs = updatedAt <= 0 ? -1 : System.currentTimeMillis() - updatedAt;
        boolean online = ageMs >= 0 && ageMs <= staleAfterMs;
        String broadcastMode = summary.getOrDefault("broadcastMode", "unknown");

        List<String> warnings = new ArrayList<>();
        if (!online) {
            warnings.add("心跳已停止 " + ageMs + "ms, 超过判活阈值 " + staleAfterMs + "ms");
        }
        if ("off".equals(broadcastMode)) {
            warnings.add("集群广播已关闭: 本节点发布的消息不会跨节点 —— "
                    + "若存在跨节点 MQTT 订阅, 表现为消息静默不到(见「跨节点订阅重叠」检查)");
        }
        long offlineDropped = parseLong(summary.get("offlineMessagesDropped"), 0L);
        if (offlineDropped > 0) {
            warnings.add("已丢弃 " + offlineDropped + " 条离线 QoS 1/2 消息, QoS 保证已破损");
        }
        long queueFullDropped = parseLong(summary.get("backpressure.sendQueueFullDropped"), 0L);
        if (queueFullDropped > 0) {
            warnings.add("发送队列满而丢弃 " + queueFullDropped + " 次, 说明有客户端确认速度跟不上投递速度");
        }
        long busDropped = parseLong(summary.get("clusterBus.dropped"), 0L);
        if (busDropped > 0) {
            warnings.add("集群总线丢弃 " + busDropped + " 条, 跨节点投递已不完整");
        }
        long flushFailed = parseLong(summary.get("inflight.flushFailed"), 0L);
        if (flushFailed > 0) {
            warnings.add("在途镜像刷盘失败 " + flushFailed + " 次");
        }
        long connections = parseLong(summary.get("connections"), -1L);
        long rawConnections = parseLong(summary.get("rawConnections"), -1L);
        if (connections >= 0 && rawConnections > connections) {
            warnings.add("有 " + (rawConnections - connections)
                    + " 条 TCP 连接尚未完成 CONNECT 握手(连接数差值), 可能是扫描或客户端异常");
        }

        return new NodeView(
                nodeId,
                online,
                ageMs,
                connections,
                rawConnections,
                parseLong(summary.get("sessions"), -1L),
                parseLong(summary.get("subscriptions"), -1L),
                parseLong(summary.get("topicNodes"), -1L),
                parseLong(summary.get("retainMessages"), -1L),
                clientEntries,
                filterEntries,
                broadcastMode,
                parseBoolean(summary.get("clusterEnabled")),
                parseBoolean(summary.get("clusterBusEnabled")),
                parseBoolean(summary.get("uplinkEnabled")),
                parseLong(summary.get("startedAt"), 0L),
                updatedAt,
                summary,
                List.copyOf(warnings));
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

    private static boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value);
    }
}

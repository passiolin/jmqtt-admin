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
package online.ipuff.jmqtt.admin.drain;

import java.util.List;
import java.util.Map;

/**
 * 排水任务的对外视图。
 *
 * <h2>为什么不直接把 {@link DrainSession} 序列化出去</h2>
 * 会话对象是可变的、由调度线程持续修改的。直接序列化它意味着 JSON 里可能出现
 * 「读了 half 的状态」——例如 {@code state} 已是 WAITING_RECONNECT 而
 * {@code evicted} 还没写。虽然每个字段都是 volatile 的, 但一次序列化会读多个字段,
 * 它们之间没有一致性保证。
 *
 * <p>先拍成不可变视图, 每次请求都拿到一个自洽的快照。代价是一次对象构造。
 *
 * <h2>为什么把「实测数字」一起给前端</h2>
 * {@code observedDrop} 与 {@code observedElsewhereGain} 是排水的核心判据。
 * 前端把它们和目标值并排展示, 操作者就能自己判断进展 —— 而不必等后端给出结论,
 * 更不必去猜「为什么还没完成」。
 */
public record DrainView(
        String id,
        String node,
        String mode,
        double value,
        int batchSize,
        int intervalMs,
        boolean publishWill,
        String clientIdPrefix,
        String state,
        String message,
        long createdAt,
        long updatedAt,
        long startedAt,
        long finishedAt,
        boolean terminal,
        Map<String, Long> baseline,
        Map<String, Long> latestCounts,
        long observedDrop,
        long observedElsewhereGain,
        boolean precheckSuspect,
        long precheckStartCount,
        long precheckEndCount,
        String evictCommandId,
        int evictTarget,
        int evicted,
        long reconnectDeadline,
        List<Map<String, Object>> timeline
) {

    public static DrainView of(DrainSession session) {
        return new DrainView(
                session.id(),
                session.node(),
                session.mode(),
                session.value(),
                session.batchSize(),
                session.intervalMs(),
                session.publishWill(),
                session.clientIdPrefix(),
                session.state().name(),
                session.message(),
                session.createdAt(),
                session.updatedAt(),
                session.startedAt(),
                session.finishedAt(),
                session.state().terminal(),
                session.baseline(),
                session.latestCounts(),
                session.observedDrop(),
                session.observedElsewhereGain(),
                session.precheckSuspect(),
                session.precheckStartCount(),
                session.precheckEndCount(),
                session.evictCommandId(),
                session.evictTarget(),
                session.evicted(),
                session.reconnectDeadline(),
                session.timeline());
    }
}

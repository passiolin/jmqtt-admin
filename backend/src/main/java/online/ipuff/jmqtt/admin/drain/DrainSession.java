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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次「驱逐」(把某节点的客户端迁走)的流程状态。
 *
 * <h2>为什么这需要一个状态机, 而不是一个接口调用</h2>
 * 驱逐横跨了四件不同系统里的事: 负载均衡停止调度(人做的)、broker 断开连接、
 * 客户端自行重连、连接在节点间重新分布。没有任何一个系统能单独回答「现在到底成没成」。
 *
 * <p>更关键的是它<b>必须可中断、可观察、可解释</b>: 一个运维动作如果只有「开始」和
 * 「结束」两个状态, 那么当它卡住时, 操作者唯一能做的就是猜。所以这里记录阶段、
 * 基线、时间线, 以及每个阶段的实测数字 —— 卡住时要能说出卡在哪一步。
 *
 * <h2>为什么时间线(cursor)值得单独存</h2>
 * 驱逐是异步且耗时的。操作者点完之后会去干别的事, 回来时看到的是「已完成」或「超时」。
 * 那时候最需要知道的是「中间发生了什么」: 预检有没有告警、驱逐是几批完成的、
 * 其他节点的连接数有没有真的上涨。事后从日志里翻这些信息几乎不可能 ——
 * 它在多个节点、多个线程的日志里。
 */
public class DrainSession {

    /**
     * 流程阶段。
     *
     * <p>刻意把 {@link #PRECHECKED} 与 {@link #LB_CONFIRMED} 分开: 前者是「我观测完了」,
     * 后者是「人确认已经改过负载均衡」。把这两件事合成一个状态, 就没法回答
     * 「到底是没观测, 还是观测了但人还没确认」—— 而这两种情况该做的事完全不同。
     */
    public enum State {
        /** 已创建, 等待开始预检 */
        CREATED,
        /** 正在观测「新连接是否还在进来」 */
        PRECHECKING,
        /** 预检完成, 等待人工确认已停止对该节点的调度 */
        PRECHECKED,
        /** 人工已确认。可以开始驱逐 */
        LB_CONFIRMED,
        /** 驱逐执行中 */
        EVICTING,
        /** 驱逐已结束, 等待客户端重连到其他节点 */
        WAITING_RECONNECT,
        /** 全部完成 */
        COMPLETED,
        /** 等待重连超时 */
        TIMEOUT,
        /** 人工中止 */
        ABORTED,
        /** 失败(节点拒绝 / 命令过期 / 执行出错) */
        FAILED;

        public boolean terminal() {
            return this == COMPLETED || this == TIMEOUT || this == ABORTED || this == FAILED;
        }
    }

    /** 时间线条目上限。超过后丢弃最旧的 —— 这是给人看的, 不是审计日志 */
    private static final int TIMELINE_LIMIT = 60;

    private final String id;
    private final String node;
    private final String mode;
    private final double value;
    private final int batchSize;
    private final int intervalMs;
    private final boolean publishWill;
    private final String clientIdPrefix;
    private final long createdAt = System.currentTimeMillis();

    private volatile State state = State.CREATED;
    private volatile String message;
    private volatile long updatedAt = System.currentTimeMillis();
    private volatile long startedAt;
    private volatile long finishedAt;

    /** 驱逐前的各节点连接数基线。判定「客户端迁走了没有」全靠它 */
    private volatile Map<String, Long> baseline = Map.of();
    private volatile Map<String, Long> latestCounts = Map.of();

    private volatile long precheckStartAt;
    private volatile long precheckStartCount = -1;
    private volatile long precheckEndCount = -1;
    private volatile boolean precheckSuspect;
    private final AtomicBoolean abortRequested = new AtomicBoolean();

    private volatile String evictCommandId;
    private volatile int evictTarget = -1;
    private volatile int evicted = -1;
    private volatile String evictMessage;

    private volatile long reconnectDeadline;

    private final Deque<Map<String, Object>> timeline = new ArrayDeque<>();

    public DrainSession(String id, String node, String mode, double value, int batchSize,
                        int intervalMs, boolean publishWill, String clientIdPrefix) {
        this.id = id;
        this.node = node;
        this.mode = mode;
        this.value = value;
        this.batchSize = batchSize;
        this.intervalMs = intervalMs;
        this.publishWill = publishWill;
        this.clientIdPrefix = clientIdPrefix;
    }

    // ------------------------------------------------------------------
    // 时间线
    // ------------------------------------------------------------------

    public void log(String text) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("at", System.currentTimeMillis());
        entry.put("state", state.name());
        entry.put("text", text);
        synchronized (timeline) {
            if (timeline.size() >= TIMELINE_LIMIT) {
                timeline.removeFirst();
            }
            timeline.addLast(entry);
        }
        this.updatedAt = System.currentTimeMillis();
    }

    public List<Map<String, Object>> timeline() {
        synchronized (timeline) {
            return new ArrayList<>(timeline);
        }
    }

    // ------------------------------------------------------------------
    // 推进
    // ------------------------------------------------------------------

    public void transition(State next, String text) {
        this.state = next;
        this.message = text;
        log(text);
    }

    public void finish(State finalState, String text) {
        this.state = finalState;
        this.message = text;
        this.finishedAt = System.currentTimeMillis();
        log(text);
    }

    // ---- 读取 ----

    public String id() {
        return id;
    }

    public String node() {
        return node;
    }

    public String mode() {
        return mode;
    }

    public double value() {
        return value;
    }

    public int batchSize() {
        return batchSize;
    }

    public int intervalMs() {
        return intervalMs;
    }

    public boolean publishWill() {
        return publishWill;
    }

    public String clientIdPrefix() {
        return clientIdPrefix;
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public long startedAt() {
        return startedAt;
    }

    public long finishedAt() {
        return finishedAt;
    }

    public State state() {
        return state;
    }

    public String message() {
        return message;
    }

    public Map<String, Long> baseline() {
        return baseline;
    }

    public Map<String, Long> latestCounts() {
        return latestCounts;
    }

    public long precheckStartAt() {
        return precheckStartAt;
    }

    public long precheckStartCount() {
        return precheckStartCount;
    }

    public long precheckEndCount() {
        return precheckEndCount;
    }

    public boolean precheckSuspect() {
        return precheckSuspect;
    }

    public String evictCommandId() {
        return evictCommandId;
    }

    public int evictTarget() {
        return evictTarget;
    }

    public int evicted() {
        return evicted;
    }

    public String evictMessage() {
        return evictMessage;
    }

    public long reconnectDeadline() {
        return reconnectDeadline;
    }

    public boolean abortRequested() {
        return abortRequested.get();
    }

    // ---- 写入 ----

    public void requestAbort() {
        abortRequested.set(true);
    }

    public void markStarted(long at) {
        this.startedAt = at;
    }

    public void setBaseline(Map<String, Long> baseline) {
        this.baseline = Map.copyOf(baseline);
    }

    public void setLatestCounts(Map<String, Long> counts) {
        this.latestCounts = Map.copyOf(counts);
        this.updatedAt = System.currentTimeMillis();
    }

    public void beginPrecheck(long at, long startCount) {
        this.precheckStartAt = at;
        this.precheckStartCount = startCount;
    }

    public void endPrecheck(long endCount, boolean suspect) {
        this.precheckEndCount = endCount;
        this.precheckSuspect = suspect;
    }

    public void setEvictCommand(String commandId, int target) {
        this.evictCommandId = commandId;
        this.evictTarget = target;
    }

    public void setEvictOutcome(int evicted, String message) {
        this.evicted = evicted;
        this.evictMessage = message;
    }

    public void setReconnectDeadline(long deadline) {
        this.reconnectDeadline = deadline;
    }

    /**
     * 目标节点当前连接数相对基线的下降量。
     *
     * @return 下降量; 拿不到数据时返回 -1
     */
    public long observedDrop() {
        Long before = baseline.get(node);
        Long now = latestCounts.get(node);
        if (before == null || now == null) {
            return -1;
        }
        return before - now;
    }

    /**
     * 其他节点连接数相对基线的合计变化。
     *
     * <p>只有<b>在线</b>节点参与合计, 且基线里没有的节点(驱逐期间新扩的)也计入 ——
     * 后者是好事, 不该被当成异常。
     */
    public long observedElsewhereGain() {
        long gain = 0;
        boolean any = false;
        for (Map.Entry<String, Long> entry : latestCounts.entrySet()) {
            if (entry.getKey().equals(node)) {
                continue;
            }
            Long before = baseline.getOrDefault(entry.getKey(), 0L);
            gain += entry.getValue() - before;
            any = true;
        }
        return any ? gain : -1;
    }
}

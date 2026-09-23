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

import online.ipuff.jmqtt.admin.AdminProperties;
import org.springframework.stereotype.Component;

/**
 * 与 broker 共享的 Redis 键约定。
 *
 * <h2>这是一份契约, 而且没有编译期检查</h2>
 * 本类与 broker 侧的 {@code online.ipuff.jmqtt.admin.AdminRedisKeys} 必须保持一致:
 * 两个工程是独立的 jar, 没有任何共享代码, 唯一把它们绑在一起的就是「键长什么样」。
 * 因此改动键名时<b>必须同时改两处</b>。
 *
 * <p>把这份约定复制一份而不是抽公共模块, 是刻意的取舍: 抽取会引入一个
 * 「为了 6 个字符串而存在的依赖包」, 而那种包的版本管理成本通常高于它挡住的风险。
 * 代价是本次注释所提示的这一点 —— 它靠人记住, 所以写在这里。
 *
 * <pre>
 * {prefix}:admin:nodes             SET    全部曾注册过的节点 id
 * {prefix}:admin:node:{nodeId}     HASH   心跳 + 节点概要, TTL = broker 的 state-ttl-seconds
 * {prefix}:admin:clients:{nodeId}  HASH   clientId -> 紧凑 JSON
 * {prefix}:admin:filters:{nodeId}  HASH   topicFilter -> 订阅者数
 * {prefix}:admin:cmd:{nodeId}      LIST   控制台下发的命令(控制台 LPUSH, 节点 RPOP)
 * {prefix}:admin:cmdr:{cmdId}      HASH   命令结果与进度
 * </pre>
 *
 * <h2>为什么控制台只读、命令只走 LIST</h2>
 * 控制台除了往命令队列里放东西, <b>不修改任何 broker 状态</b>。
 * 这意味着即使控制台被误操作或出现 bug, 最坏后果也只是「下发了一条不该发的命令」,
 * 而不会出现「控制台把某台机器的状态改坏了」—— 后者根本无法通过重启控制台恢复。
 */
@Component
public class AdminKeys {

    private final String prefix;

    public AdminKeys(AdminProperties properties) {
        this.prefix = properties.redis().keyPrefix();
    }

    public String nodes() {
        return prefix + ":admin:nodes";
    }

    public String node(String nodeId) {
        return prefix + ":admin:node:" + nodeId;
    }

    public String clients(String nodeId) {
        return prefix + ":admin:clients:" + nodeId;
    }

    public String filters(String nodeId) {
        return prefix + ":admin:filters:" + nodeId;
    }

    /** 全部监听任务 id */
    public String captures() {
        return prefix + ":admin:captures";
    }

    /** 监听任务元数据 */
    public String capture(String captureId) {
        return prefix + ":admin:capture:" + captureId;
    }

    /** 监听到的消息列表 */
    public String captureMessages(String captureId) {
        return prefix + ":admin:capture:" + captureId + ":msgs";
    }

    public String commandQueue(String nodeId) {
        return prefix + ":admin:cmd:" + nodeId;
    }

    public String commandResult(String commandId) {
        return prefix + ":admin:cmdr:" + commandId;
    }
}

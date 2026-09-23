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
package online.ipuff.jmqtt.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * jmqtt-admin 启动入口。
 *
 * <h2>控制台的定位: 只读 Redis + 只写命令</h2>
 * 它<b>不直连任何 broker</b>。要看到的东西都在 Redis 里(broker 发布的状态),
 * 要做的事也写进 Redis(broker 轮询的命令队列)。这个约束带来三个直接好处:
 * <ol>
 *   <li><b>控制台不需要访问 broker 的管理端口。</b>生产里这些端口通常不在同一张网内,
 *       而 Redis 是双方都必须能到的 —— 它本来就承担着跨节点会话持久化。</li>
 *   <li><b>「节点是否可达」由心跳表达, 而不是由请求超时表达。</b>
 *       后者无法区分「节点挂了」与「网络抖了一下」, 而前者可以精确到秒。</li>
 *   <li><b>控制台重启不影响任何进行中的运维动作。</b>驱逐是 broker 在执行,
 *       控制台只是在旁边看进度; 控制台掉线不会让驱逐停下来。</li>
 * </ol>
 *
 * <h2>驱逐流程为什么需要控制台来编排</h2>
 * 「停调度 → 驱逐 → 等待重连 → 校验」这条链路横跨了负载均衡、N 个 broker 与时间,
 * 单看任何一个 broker 都无法回答「现在到底成不成」。控制台是唯一同时握有
 * 「各节点连接数快照」与「驱逐进度」的地方, 因此流程状态机放在这里。
 */
@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(AdminProperties.class)
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}

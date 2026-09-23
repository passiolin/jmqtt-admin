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

import javax.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 控制台配置。
 *
 * <h2>控制台为什么没有「节点列表」配置</h2>
 * 这是本设计里最刻意的一处取舍。<b>节点集合是从 Redis 里发现的</b>:
 * broker 启动时把自己的 id 写进 {@code {prefix}:admin:nodes}, 之后靠心跳续期。
 *
 * <p>如果改成在控制台配置里静态列出节点, 会立刻出现三个问题:
 * <ol>
 *   <li><b>扩容要在两个地方改。</b>加一个 broker 要改控制台配置并重启它,
 *       而控制台重启意味着所有进行中的驱逐流程状态丢失。</li>
 *   <li><b>「配置里有、实际不存在」的节点会被显示成在线或离线, 而不是「不存在」。</b>
 *       运维看到的是一个说的通但错的视图 —— 比看不到更糟。</li>
 *   <li><b>节点挂了之后配置项还留着, 而它永远不会再回来。</b></li>
 * </ol>
 * 反过来, 从 Redis 发现的代价是「控制台必须能连上 Redis」。这个前提本来就成立 ——
 * 控制台读的所有数据都在那里。
 *
 * @param username                     登录用户名
 * @param password                     登录密码。放在配置文件里, 仅适用于内网管理台;
 *                                     若要暴露到更大范围, 应改为接企业统一登录
 * @param tokenTtlSeconds              登录令牌有效期
 * @param redis                        Redis 连接(必须与 broker 指向同一个库、同一个 key 前缀)
 * @param maxEvictPerTask              控制台侧的单次驱逐上限。与 broker 的同名配置是<b>双重闸门</b>:
 *                                     控制台先拦一次给出友好提示, broker 再兜一次防止绕过控制台直连 Redis
 * @param commandMaxAgeMs              命令最大容忍年龄, 与 broker 侧配合决定「超龄命令被拒绝」的边界
 * @param defaultEvictBatchSize        默认每批驱逐数量
 * @param defaultEvictIntervalMs       默认批次间隔
 * @param maxScanCount                 单次 HSCAN 最多返回多少条。HSCAN 是游标式的,
 *                                     这里限制的是「一次请求最多给多少」, 防止一个页面把几十万条全拉出来
 * @param nodeStaleAfterMs             心跳超过多久未更新就视为离线。
 *                                     为什么不能只靠「键是否存在」: 节点进程卡死(例如长时间 Full GC)
 *                                     时键还没到 TTL, 但它已经不在正常工作了 —— 这段窗口里
 *                                     控制台会拿一份不再更新的数据进行判断
 * @param precheckWindowMs             预检观测窗口。用来观察「停止调度之后, 新连接是否还在进来」
 * @param precheckGrowthThreshold      窗口内新增连接数超过该值即判定「LB 可能没有真正停止调度」
 * @param reconnectTimeoutSeconds      等待客户端重连到其他节点的时限
 * @param reconnectPollIntervalMs      驱逐流程的推进间隔
 */
@ConfigurationProperties(prefix = "jmqtt.admin")
public record AdminProperties(
        @DefaultValue("jmqtt") String username,
        @DefaultValue("jmqtt") String password,
        @DefaultValue("28800") @Min(60) long tokenTtlSeconds,
        @DefaultValue RedisProperties redis,
        @DefaultValue("5000") @Min(1) int maxEvictPerTask,
        @DefaultValue("60000") @Min(1000) long commandMaxAgeMs,
        @DefaultValue("200") @Min(1) int defaultEvictBatchSize,
        @DefaultValue("500") @Min(50) int defaultEvictIntervalMs,
        @DefaultValue("2000") @Min(10) int maxScanCount,
        @DefaultValue("25000") @Min(1000) long nodeStaleAfterMs,
        @DefaultValue("8000") @Min(1000) long precheckWindowMs,
        @DefaultValue("5") @Min(0) int precheckGrowthThreshold,
        @DefaultValue("180") @Min(10) int reconnectTimeoutSeconds,
        @DefaultValue("2000") @Min(200) long reconnectPollIntervalMs
) {

    /**
     * Redis 连接参数。
     *
     * <p><b>{@code keyPrefix} 必须与 broker 的 {@code jmqtt.broker.redis.key-prefix} 一致。</b>
     * 控制台与 broker 是两个独立进程, 它们之间唯一的契约就是「键长什么样」——
     * 这个契约没有编译期检查, 配错了表现为「控制台空无一人」而不是报错。
     *
     * @param host             Redis 地址(standalone 模式)
     * @param port             Redis 端口(standalone 模式)
     * @param password         密码, 留空表示无密码
     * @param database         库号(集群模式必须为 0 —— Redis Cluster 只有 db0)
     * @param keyPrefix        键前缀, 必须与 broker 一致
     * @param commandTimeoutMs 命令超时。控制台是只读方, 超时设大一点无妨,
     *                         但要有限: 否则一个卡住的 Redis 会拖住所有前端请求
     * @param mode             部署模式: {@code standalone}(默认)/{@code sentinel}/
     *                         {@code cluster}。与 broker 的 redis.mode 语义一致
     * @param masterId         哨兵模式的主节点名称(sentinel monitor 的名字), 仅 sentinel 需要
     * @param nodes            哨兵/集群的节点地址列表(host:port)。sentinel 列哨兵进程,
     *                         cluster 列种子节点(任一可达即可)
     */
    public record RedisProperties(
            @DefaultValue("127.0.0.1") String host,
            @DefaultValue("6379") @Min(1) int port,
            @DefaultValue("") String password,
            @DefaultValue("0") @Min(0) int database,
            @DefaultValue("jmqtt") String keyPrefix,
            @DefaultValue("3000") @Min(100) int commandTimeoutMs,
            @DefaultValue("standalone") String mode,
            @DefaultValue("") String masterId,
            @DefaultValue("[]") java.util.List<String> nodes
    ) {

        public boolean sentinelMode() {
            return "sentinel".equalsIgnoreCase(mode);
        }

        public boolean clusterMode() {
            return "cluster".equalsIgnoreCase(mode);
        }
    }
}

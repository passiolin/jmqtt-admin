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
package online.ipuff.jmqtt.admin.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import online.ipuff.jmqtt.admin.AdminProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Lettuce 客户端配置。
 *
 * <p>控制台与 broker 用的是同一个 Redis 库, 但连接参数刻意不共享 ——
 * 它们对超时的诉求相反:
 * <ul>
 *   <li>broker 的命令在 MQTT 处理路径上, 超时必须<b>短</b>(秒级以下), 宁可降级也不能拖慢握手;</li>
 *   <li>控制台的命令在人工操作路径上, 超时可以<b>长</b>一些 ——
 *       一次 HSCAN 扫几万条字段比 PING 慢得多, 用 broker 那个超时值会误报故障。</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient adminRedisClient(AdminProperties properties) {
        AdminProperties.RedisProperties redis = properties.redis();
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(redis.host())
                .withPort(redis.port())
                .withDatabase(redis.database())
                .withTimeout(Duration.ofMillis(redis.commandTimeoutMs()));
        if (redis.password() != null && !redis.password().isEmpty()) {
            builder.withPassword(redis.password().toCharArray());
        }
        RedisClient client = RedisClient.create(builder.build());
        // 默认的自动重连在「Redis 确实挂了」时会不断重试, 把控制台的错误日志刷满 ——
        // 控制台的降级策略是「明确告诉操作者连不上」, 而不是偷偷重试
        client.setOptions(ClientOptions.builder()
                .autoReconnect(false)
                .timeoutOptions(TimeoutOptions.enabled(
                        Duration.ofMillis(redis.commandTimeoutMs())))
                .build());
        return client;
    }
}

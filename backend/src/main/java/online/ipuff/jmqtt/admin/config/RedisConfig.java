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
package online.ipuff.jmqtt.admin.config;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import online.ipuff.jmqtt.admin.AdminProperties;
import online.ipuff.jmqtt.admin.redis.AdminRedisSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Lettuce 客户端配置, 支持三种部署模式({@code redis.mode}):
 * standalone(默认)/ sentinel / cluster, 语义与 broker 的 redis.mode 一致。
 *
 * <p>控制台与 broker 用的是同一个 Redis 库, 但连接参数刻意不共享 ——
 * 它们对超时的诉求相反:
 * <ul>
 *   <li>broker 的命令在 MQTT 处理路径上, 超时必须<b>短</b>(秒级以下), 宁可降级也不能拖慢握手;</li>
 *   <li>控制台的命令在人工操作路径上, 超时可以<b>长</b>一些 ——
 *       一次 HSCAN 扫几万条字段比 PING 慢得多, 用 broker 那个超时值会误报故障。</li>
 * </ul>
 *
 * <p>配置错误(未知模式、哨兵缺 master-id/nodes、集群配了 database、节点格式非法)
 * 启动即失败 —— 控制台的数据全在 Redis, 错配静默通过的表现是
 * 「空无一人」的假视图, 比报错危险得多。
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Bean(destroyMethod = "close")
    public AdminRedisSource adminRedisSource(AdminProperties properties) {
        AdminProperties.RedisProperties redis = properties.redis();
        AbstractRedisClient client = createClient(redis);

        // 默认的自动重连在「Redis 确实挂了」时会不断重试, 把控制台的错误日志刷满 ——
        // 控制台的降级策略是「明确告诉操作者连不上」, 而不是偷偷重试。
        // setOptions 在各具体客户端类上才是 public, 按类型分别设置
        if (client instanceof RedisClusterClient cluster) {
            cluster.setOptions(ClusterClientOptions.builder()
                    .autoReconnect(false)
                    .timeoutOptions(TimeoutOptions.enabled(
                            Duration.ofMillis(redis.commandTimeoutMs())))
                    .build());
        } else {
            ((RedisClient) client).setOptions(ClientOptions.builder()
                    .autoReconnect(false)
                    .timeoutOptions(TimeoutOptions.enabled(
                            Duration.ofMillis(redis.commandTimeoutMs())))
                    .build());
        }

        log.info("Redis 客户端已创建(惰性连接): mode={} {}", modeOf(redis), describeTargets(redis));
        return new AdminRedisSource(client);
    }

    private static AbstractRedisClient createClient(AdminProperties.RedisProperties redis) {
        if (redis.clusterMode()) {
            requireNodes(redis, "cluster");
            if (redis.database() != 0) {
                throw new IllegalStateException(
                        "redis.mode=cluster 但 database=" + redis.database()
                                + " —— Redis Cluster 只有 db0, 请将 database 置 0 或改用 standalone/sentinel 模式。");
            }
            List<RedisURI> seeds = new ArrayList<>();
            for (String node : redis.nodes()) {
                String[] hostPort = parseNode(node);
                seeds.add(uri(hostPort[0], Integer.parseInt(hostPort[1]), 0, redis));
            }
            return RedisClusterClient.create(seeds);
        }
        if (redis.sentinelMode()) {
            if (redis.masterId() == null || redis.masterId().isBlank()) {
                throw new IllegalStateException(
                        "redis.mode=sentinel 但未配置 master-id(哨兵 monitor 的主节点名称)。");
            }
            requireNodes(redis, "sentinel");
            RedisURI.Builder builder = RedisURI.Builder.sentinel(redis.masterId())
                    .withDatabase(redis.database())
                    .withTimeout(Duration.ofMillis(redis.commandTimeoutMs()));
            applyPassword(builder, redis);
            for (String node : redis.nodes()) {
                String[] hostPort = parseNode(node);
                builder.withSentinel(hostPort[0], Integer.parseInt(hostPort[1]));
            }
            return RedisClient.create(builder.build());
        }
        if (!"standalone".equalsIgnoreCase(redis.mode())) {
            throw new IllegalStateException("未知的 redis.mode: " + redis.mode()
                    + " —— 支持 standalone / sentinel / cluster。");
        }
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(redis.host())
                .withPort(redis.port())
                .withDatabase(redis.database())
                .withTimeout(Duration.ofMillis(redis.commandTimeoutMs()));
        applyPassword(builder, redis);
        return RedisClient.create(builder.build());
    }

    private static RedisURI uri(String host, int port, int database, AdminProperties.RedisProperties redis) {
        RedisURI.Builder builder = RedisURI.Builder.redis(host, port)
                .withDatabase(database)
                .withTimeout(Duration.ofMillis(redis.commandTimeoutMs()));
        applyPassword(builder, redis);
        return builder.build();
    }

    private static void applyPassword(RedisURI.Builder builder, AdminProperties.RedisProperties redis) {
        if (redis.password() != null && !redis.password().isEmpty()) {
            builder.withPassword(redis.password().toCharArray());
        }
    }

    private static void requireNodes(AdminProperties.RedisProperties redis, String mode) {
        if (redis.nodes() == null || redis.nodes().isEmpty()) {
            throw new IllegalStateException(
                    "redis.mode=" + mode + " 但未配置 nodes(节点地址列表, 形如 10.0.0.6:6379)。");
        }
    }

    private static String[] parseNode(String node) {
        if (node != null) {
            String[] parts = node.split(":", 2);
            if (parts.length == 2 && parts[0] != null && !parts[0].isBlank()
                    && parts[1].matches("\\d{1,5}")) {
                return parts;
            }
        }
        throw new IllegalStateException(
                "redis.nodes 中的节点地址非法: \"" + node + "\" —— 应为 host:port 形式(如 10.0.0.6:6379)。");
    }

    private static String modeOf(AdminProperties.RedisProperties redis) {
        return redis.mode() == null ? "standalone" : redis.mode();
    }

    private static String describeTargets(AdminProperties.RedisProperties redis) {
        if (redis.clusterMode() || redis.sentinelMode()) {
            return (redis.clusterMode() ? "seeds=" : "sentinels=") + redis.nodes();
        }
        return "target=" + redis.host() + ":" + redis.port();
    }
}

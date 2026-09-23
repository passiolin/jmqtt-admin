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

import online.ipuff.jmqtt.admin.AdminProperties;
import online.ipuff.jmqtt.admin.redis.AdminRedisSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 三种部署模式(单机/哨兵/集群)的装配与 fail-fast 校验。
 * 客户端构造是惰性连接的, 三种模式都能在无 Redis 的测试环境里完成装配;
 * 配置错误必须启动即失败 —— 控制台的数据全在 Redis, 错配静默通过的表现是
 * 「空无一人」的假视图, 比报错危险得多。
 */
class RedisModeConfigTest {

    private static final RedisConfig CONFIG = new RedisConfig();

    private static AdminProperties.RedisProperties redis(String mode, String masterId,
                                                         List<String> nodes, int database) {
        return new AdminProperties.RedisProperties("127.0.0.1", 6379, null, database,
                "jmqtt", 3000, mode, masterId, nodes);
    }

    @Test
    @DisplayName("单机模式(默认): 装配成功")
    void standaloneMode() {
        assertNotNull(CONFIG.adminRedisSource(props(redis("standalone", null, null, 0))));
    }

    @Test
    @DisplayName("哨兵模式: 配置齐全装配成功")
    void sentinelMode() {
        assertNotNull(CONFIG.adminRedisSource(props(redis("sentinel", "mymaster",
                List.of("10.0.0.6:26379", "10.0.0.7:26379"), 0))));
    }

    @Test
    @DisplayName("集群模式: 配置齐全装配成功")
    void clusterMode() {
        assertNotNull(CONFIG.adminRedisSource(props(redis("cluster", null,
                List.of("10.0.0.6:6379", "10.0.0.7:6379"), 0))));
    }

    @Test
    @DisplayName("未知模式: 启动即失败")
    void unknownModeFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> CONFIG.adminRedisSource(props(redis("sharded", null, null, 0))));
    }

    @Test
    @DisplayName("哨兵缺 master-id 或 nodes: 启动即失败")
    void sentinelMissingMasterOrNodesFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> CONFIG.adminRedisSource(props(redis("sentinel", null,
                        List.of("10.0.0.6:26379"), 0))));
        assertThrows(IllegalStateException.class,
                () -> CONFIG.adminRedisSource(props(redis("sentinel", "mymaster", List.of(), 0))));
    }

    @Test
    @DisplayName("集群模式 database != 0: 启动即失败(集群只有 db0)")
    void clusterWithDatabaseFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CONFIG.adminRedisSource(props(redis("cluster", null,
                        List.of("10.0.0.6:6379"), 2))));
        assertTrue(e.getMessage().contains("db0"), e.getMessage());
    }

    @Test
    @DisplayName("nodes 格式非法: 启动即失败并指明是哪个节点")
    void malformedNodeFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CONFIG.adminRedisSource(props(redis("cluster", null,
                        List.of("10.0.0.6-no-port"), 0))));
        assertTrue(e.getMessage().contains("10.0.0.6-no-port"), e.getMessage());
    }

    private static AdminProperties props(AdminProperties.RedisProperties redis) {
        return new AdminProperties("jmqtt", "jmqtt", 28800, redis,
                5000, 60000, 200, 500, 2000, 25000, 8000, 5, 180, 2000);
    }
}

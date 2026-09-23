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
package online.ipuff.jmqtt.admin.registry;

import online.ipuff.jmqtt.admin.AdminProperties;
import online.ipuff.jmqtt.admin.redis.AdminKeys;
import online.ipuff.jmqtt.admin.redis.AdminRedis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线节点删除的守卫条件 —— 核心是「在线节点绝不能删」,
 * 以及删除时把该节点的全部遗留键一起清掉。
 */
class NodeRegistryServiceTest {

    /** 用子类覆写代替 mock: 只覆写服务用到的几个方法, 内存状态当 Redis */
    private static final class FakeRedis extends AdminRedis {
        final Set<String> nodes = new HashSet<>();
        final Map<String, Map<String, String>> hashes = new HashMap<>();
        final Set<String> deleted = new HashSet<>();

        FakeRedis() {
            super(null, props(), null);
        }

        @Override
        public Set<String> smembers(String key) {
            return new HashSet<>(nodes);
        }

        @Override
        public Map<String, String> hgetAll(String key) {
            return hashes.getOrDefault(key, Map.of());
        }

        @Override
        public boolean srem(String key, String member) {
            return nodes.remove(member);
        }

        @Override
        public long del(String... keys) {
            long n = 0;
            for (String key : keys) {
                deleted.add(key);
                if (hashes.remove(key) != null) {
                    n++;
                }
            }
            return n;
        }
    }

    private static AdminProperties props() {
        return new AdminProperties("jmqtt", "jmqtt", 28800,
                new AdminProperties.RedisProperties("127.0.0.1", 6379, "", 0,
                        "jmqtt", 3000, "standalone", "", java.util.List.of()),
                5000, 60000, 200, 500, 2000, 25000, 8000, 5, 180, 2000);
    }

    /** 一份「心跳新鲜」的节点概要 */
    private static Map<String, String> freshSummary() {
        return Map.of("updatedAt", String.valueOf(System.currentTimeMillis()));
    }

    @Test
    @DisplayName("不存在的节点: 拒绝并说明")
    void unknownNodeRejected() {
        FakeRedis redis = new FakeRedis();
        redis.nodes.add("other");
        NodeRegistryService service = new NodeRegistryService(redis, new AdminKeys(props()), props());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.removeOffline("ghost"));
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
    }

    @Test
    @DisplayName("在线节点(心跳新鲜): 绝不能删 —— 注册表被删会让总览和排水基线短暂失真")
    void onlineNodeRejected() {
        FakeRedis redis = new FakeRedis();
        redis.nodes.add("node-a");
        redis.hashes.put("jmqtt:admin:node:node-a", freshSummary());
        AdminProperties properties = props();
        NodeRegistryService service = new NodeRegistryService(redis, new AdminKeys(properties), properties);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.removeOffline("node-a"));
        assertTrue(e.getMessage().contains("仍在线"), e.getMessage());
        assertTrue(redis.nodes.contains("node-a"), "注册表不得被改动");
    }

    @Test
    @DisplayName("离线节点: 删注册 + 清理全部遗留键(概要/客户端/过滤器/命令队列)")
    void offlineNodeRemovedWithKeys() {
        FakeRedis redis = new FakeRedis();
        redis.nodes.add("node-dead");
        redis.nodes.add("node-alive");
        redis.hashes.put("jmqtt:admin:node:node-alive", freshSummary());
        AdminProperties properties = props();
        NodeRegistryService service = new NodeRegistryService(redis, new AdminKeys(properties), properties);

        service.removeOffline("node-dead");

        assertTrue(redis.nodes.contains("node-alive"), "其他节点不受影响");
        assertEquals(java.util.Set.of("node-alive"), redis.nodes);
        assertEquals(java.util.Set.of(
                "jmqtt:admin:node:node-dead",
                "jmqtt:admin:clients:node-dead",
                "jmqtt:admin:filters:node-dead",
                "jmqtt:admin:cmd:node-dead"), redis.deleted, "四个遗留键全部清理");
    }
}

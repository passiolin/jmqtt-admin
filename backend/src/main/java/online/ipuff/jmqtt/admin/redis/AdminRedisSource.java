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
package online.ipuff.jmqtt.admin.redis;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.KeyValue;
import io.lettuce.core.MapScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis 连接来源: 屏蔽单机 / 哨兵 / 集群的客户端差异。
 *
 * <p>哨兵与单机用 {@link RedisClient}(URI 构造不同), 集群用 {@link RedisClusterClient};
 * {@link #open} 返回「连接本体 + {@link AdminCommands} 命令视图」的对子,
 * 命令视图由薄适配器委托实现。客户端惰性连接: 构造不建连, 首次 {@link #open} 才连。
 */
public final class AdminRedisSource implements AutoCloseable {

    private final AbstractRedisClient client;

    public AdminRedisSource(AbstractRedisClient client) {
        this.client = client;
    }

    /** 打开一条连接(线程安全多路复用, 由 {@link AdminRedis} 单点管理) */
    public Handle open() {
        if (client instanceof RedisClusterClient cluster) {
            StatefulRedisClusterConnection<String, String> connection = cluster.connect();
            return new Handle(connection, new ClusterCommands(connection.sync()));
        }
        StatefulRedisConnection<String, String> connection = ((RedisClient) client).connect();
        return new Handle(connection, new StandaloneCommands(connection.sync()));
    }

    @Override
    public void close() {
        client.shutdown();
    }

    /** 一条已打开的连接: 本体(isOpen 判断与生命周期)与命令视图 */
    public record Handle(StatefulConnection<String, String> connection, AdminCommands commands) {
    }

    private record StandaloneCommands(RedisCommands<String, String> c) implements AdminCommands {
        @Override
        public String ping() {
            return c.ping();
        }

        @Override
        public Map<String, String> hgetall(String key) {
            return c.hgetall(key);
        }

        @Override
        public List<KeyValue<String, String>> hmget(String key, String... fields) {
            return c.hmget(key, fields);
        }

        @Override
        public Long exists(String key) {
            return c.exists(key);
        }

        @Override
        public Set<String> smembers(String key) {
            return c.smembers(key);
        }

        @Override
        public Long hlen(String key) {
            return c.hlen(key);
        }

        @Override
        public MapScanCursor<String, String> hscan(String key, ScanCursor cursor, ScanArgs args) {
            return c.hscan(key, cursor, args);
        }

        @Override
        public Long lpush(String key, String... values) {
            return c.lpush(key, values);
        }

        @Override
        public String ltrim(String key, long start, long stop) {
            return c.ltrim(key, start, stop);
        }
    }

    private record ClusterCommands(RedisAdvancedClusterCommands<String, String> c) implements AdminCommands {
        @Override
        public String ping() {
            return c.ping();
        }

        @Override
        public Map<String, String> hgetall(String key) {
            return c.hgetall(key);
        }

        @Override
        public List<KeyValue<String, String>> hmget(String key, String... fields) {
            return c.hmget(key, fields);
        }

        @Override
        public Long exists(String key) {
            return c.exists(key);
        }

        @Override
        public Set<String> smembers(String key) {
            return c.smembers(key);
        }

        @Override
        public Long hlen(String key) {
            return c.hlen(key);
        }

        @Override
        public MapScanCursor<String, String> hscan(String key, ScanCursor cursor, ScanArgs args) {
            return c.hscan(key, cursor, args);
        }

        @Override
        public Long lpush(String key, String... values) {
            return c.lpush(key, values);
        }

        @Override
        public String ltrim(String key, long start, long stop) {
            return c.ltrim(key, start, stop);
        }
    }
}

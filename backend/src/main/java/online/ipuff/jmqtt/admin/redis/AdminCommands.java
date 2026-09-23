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

import io.lettuce.core.KeyValue;
import io.lettuce.core.MapScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 控制台用到的 Redis 同步命令面(自有窄门面)。
 *
 * <p>存在的原因与 broker 相同: Lettuce 的单机与集群同步接口
 * ({@code RedisCommands} 与 {@code RedisAdvancedClusterCommands})是平行层次,
 * 没有公共命令父接口, Java 的名义类型也不允许「两边都有这些方法」就统一赋值。
 * 定义自己的门面 + 两个薄适配器, 模式差异被关在适配器里,
 * {@link AdminRedis} 与全部业务代码不感知部署模式。
 *
 * <p>只声明控制台实际用到的命令 —— 面越窄越清楚。
 */
public interface AdminCommands {

    String ping();

    Map<String, String> hgetall(String key);

    List<KeyValue<String, String>> hmget(String key, String... fields);

    Long exists(String key);

    Set<String> smembers(String key);

    Long hlen(String key);

    MapScanCursor<String, String> hscan(String key, ScanCursor cursor, ScanArgs args);

    Long srem(String key, String member);

    Long del(String... keys);

    Long lpush(String key, String... values);

    List<String> lrange(String key, long start, long stop);

    String lindex(String key, long index);

    String ltrim(String key, long start, long stop);

    boolean expire(String key, long seconds);
}

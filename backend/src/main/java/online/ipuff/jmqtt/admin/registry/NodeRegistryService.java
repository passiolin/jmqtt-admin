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
import online.ipuff.jmqtt.admin.query.NodeView;
import online.ipuff.jmqtt.admin.redis.AdminKeys;
import online.ipuff.jmqtt.admin.redis.AdminRedis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 节点注册表维护: 把<b>确认离线</b>的节点从 {@code {prefix}:admin:nodes} 里删掉。
 *
 * <h2>为什么需要它</h2>
 * 注册表是 SET + 无 TTL, broker 侧只增不减 —— {@code kill -9}、断电、退役的节点
 * 会永远躺在总览里(显示为 offline)。在线判定靠概要 hash 的 TTL, 那部分是自愈的;
 * SET 成员不是。这里是人工兜底: 操作者在总览上把死节点清掉。
 *
 * <h2>只删离线的, 这条线不能松</h2>
 * 删掉一个<b>在线</b>节点的注册, 会让它从总览消失到下一次心跳(默认 5s)才回来 ——
 * 看似无害, 但这 5s 里如果有驱逐流程在做「其他节点连接数」的基线判断,
 * 少一个节点会让基线错一位。所以在线节点一律拒绝, 没有例外。
 *
 * <h2>竞态是安全的</h2>
 * 检查离线与执行删除之间, 节点恰好重启上线: 它的心跳发布每轮都会 SADD 自己,
 * 最多一个心跳周期就会重新出现在注册表里 —— 自愈, 无需加锁。
 */
@Service
public class NodeRegistryService {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistryService.class);

    private final AdminRedis redis;
    private final AdminKeys keys;
    private final AdminProperties properties;

    public NodeRegistryService(AdminRedis redis, AdminKeys keys, AdminProperties properties) {
        this.redis = redis;
        this.keys = keys;
        this.properties = properties;
    }

    /**
     * 删除一个确认离线的节点(注册表成员 + 该节点的全部遗留键)。
     *
     * @throws IllegalStateException 节点不存在, 或仍在线 —— 附带人能读懂的原因
     */
    public void removeOffline(String nodeId) {
        Set<String> members = redis.smembers(keys.nodes());
        if (!members.contains(nodeId)) {
            throw new IllegalStateException("节点不存在: " + nodeId);
        }

        Map<String, String> summary = redis.hgetAll(keys.node(nodeId));
        if (NodeView.of(nodeId, summary, -1, -1, properties.nodeStaleAfterMs()).online()) {
            throw new IllegalStateException(
                    "节点 " + nodeId + " 仍在线(心跳新鲜), 拒绝删除 —— 请等它真正下线后再清理");
        }

        redis.srem(keys.nodes(), nodeId);
        // 概要/客户端/过滤器 hash 本有 TTL 会自然过期, 显式删是让操作立即生效;
        // 命令队列 LIST 无 TTL, 是真正的遗留垃圾
        long cleaned = redis.del(keys.node(nodeId), keys.clients(nodeId),
                keys.filters(nodeId), keys.commandQueue(nodeId));
        log.info("已删除离线节点 {} 的注册(清理遗留键 {} 个)", nodeId, cleaned);
    }
}

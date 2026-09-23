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
package online.ipuff.jmqtt.admin.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.ipuff.jmqtt.admin.AdminProperties;
import online.ipuff.jmqtt.admin.redis.AdminKeys;
import online.ipuff.jmqtt.admin.redis.AdminRedis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 向节点下发命令并读取结果。
 *
 * <h2>为什么命令要自带签发时间</h2>
 * 命令走 Redis 列表, 没有 TCP 那样的连接状态保证时效。节点离线期间下发的命令会在节点
 * 回来后被执行 —— 对「驱逐」这种动作, 一小时后才执行比不执行更危险。因此每条命令都带
 * {@code issuedAt} 与 {@code maxAgeMs}, 由<b>节点侧</b>做超龄拒绝。
 *
 * <p>把超龄判断放在节点侧而不是控制台侧, 是因为只有节点知道「我是什么时候取到这条命令的」。
 * 控制台侧的「以为过期了就不发」解决不了队列里已经躺着的那一条。
 *
 * <h2>为什么队列要有长度上限</h2>
 * 见 {@link AdminRedis#pushCommand}: 连点按钮这类操作会让队列增长, 而节点一次只取一条。
 * 没有上限时, 一次误操作可以在队列里留下几百条命令, 之后节点会一条条取出来、
 * 一条条判定过期 —— 那段期间真正的命令要排队。
 */
@Service
public class NodeCommandService {

    private static final Logger log = LoggerFactory.getLogger(NodeCommandService.class);

    /**
     * 命令队列长度上限。
     *
     * <p>数值取得很小(20)是有意的: 正常情况下队列里同时不会超过 1~2 条命令;
     * 超过这个量级说明有人在连点或脚本在循环下发, 此时丢掉最老的比全部执行更安全。
     */
    private static final int MAX_QUEUE_LENGTH = 20;

    private final AdminRedis redis;
    private final AdminKeys keys;
    private final AdminProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NodeCommandService(AdminRedis redis, AdminKeys keys, AdminProperties properties) {
        this.redis = redis;
        this.keys = keys;
        this.properties = properties;
    }

    /**
     * 踢掉单个客户端。
     *
     * @param publishWill 是否按规范发布遗嘱。默认 true —— 抑制遗嘱是偏离规范的行为,
     *                    只在「确认观察方不需要这个离线信号」时使用
     * @return 命令 id; 下发失败时返回 null
     */
    /**
     * 查询某客户端<b>当下</b>的完整状态快照(含订阅列表)。
     * 按需查询 —— 订阅不实时上报, 控制台点开详情时才让节点现场构建。
     */
    public String clientDetail(String nodeId, String clientId) {
        Map<String, Object> command = base("CLIENT_DETAIL");
        command.put("clientId", clientId);
        return dispatch(nodeId, command);
    }

    /**
     * 开始一个消息监听任务(参数校验后的上限在 broker 侧再拦一次)。
     */
    public String captureStart(String nodeId, String captureId, String filter,
                               int durationMinutes, int maxMessages) {
        return captureStart(nodeId, captureId, filter, null, durationMinutes, maxMessages);
    }

    /**
     * 客户端维度 clientId 非空时, 抓该客户端发布与收到的全部消息(方向带在记录里)。
     */
    public String captureStart(String nodeId, String captureId, String filter, String clientId,
                               int durationMinutes, int maxMessages) {
        Map<String, Object> command = base("CAPTURE_START");
        Map<String, Object> capture = new java.util.HashMap<>(Map.of(
                "id", captureId,
                "durationMinutes", durationMinutes,
                "maxMessages", maxMessages));
        if (clientId != null && !clientId.isBlank()) {
            capture.put("clientId", clientId);
        } else {
            capture.put("filter", filter);
        }
        command.put("capture", capture);
        return dispatch(nodeId, command);
    }

    /** 停止一个消息监听任务(broker 停止写入, 数据保留) */
    public String captureStop(String nodeId, String captureId) {
        Map<String, Object> command = base("CAPTURE_STOP");
        command.put("captureId", captureId);
        return dispatch(nodeId, command);
    }

    public String kick(String nodeId, String clientId, boolean publishWill) {
        Map<String, Object> command = base("KICK");
        command.put("clientId", clientId);
        command.put("spec", Map.of("publishWill", publishWill));
        return dispatch(nodeId, command);
    }

    /**
     * 提交一次批量驱逐。
     *
     * @param mode            {@code ratio} 或 {@code count}
     * @param value           比例(0~1)或条数
     * @param batchSize       每批条数; null 用节点默认值
     * @param intervalMs      批次间隔; null 用节点默认值
     * @param clientIdPrefix  只驱逐匹配前缀的客户端; null 表示不筛选
     */
    public String evict(String nodeId, String mode, double value, Integer batchSize,
                        Integer intervalMs, boolean publishWill, String clientIdPrefix) {
        Map<String, Object> command = base("EVICT");
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("mode", mode);
        spec.put("value", value);
        spec.put("batchSize", batchSize);
        spec.put("intervalMs", intervalMs);
        spec.put("publishWill", publishWill);
        if (clientIdPrefix != null && !clientIdPrefix.isBlank()) {
            spec.put("clientIdPrefix", clientIdPrefix);
        }
        command.put("spec", spec);
        return dispatch(nodeId, command);
    }

    public String abortEviction(String nodeId, String evictionTaskId) {
        Map<String, Object> command = base("EVICT_ABORT");
        command.put("evictTaskId", evictionTaskId);
        return dispatch(nodeId, command);
    }

    /**
     * 探活。顺便让节点把最新概要写回 —— 常见用途是「我改完配置了, 让它立刻刷新」。
     */
    public String ping(String nodeId) {
        return dispatch(nodeId, base("PING"));
    }

    /**
     * 触发一次全量对账。用于「控制台上看到的客户端数与实际不符」时手工纠正。
     */
    public String snapshot(String nodeId) {
        return dispatch(nodeId, base("SNAPSHOT"));
    }

    /**
     * 读取命令结果。
     *
     * @return 结果字段; 命令尚未被节点取走时返回 {@code null}
     */
    public Map<String, String> result(String commandId) {
        Map<String, String> fields = redis.hgetAll(keys.commandResult(commandId));
        return fields.isEmpty() ? null : fields;
    }

    private String dispatch(String nodeId, Map<String, Object> command) {
        String commandId = String.valueOf(command.get("id"));
        try {
            String json = objectMapper.writeValueAsString(command);
            boolean pushed = redis.pushCommand(nodeId, json, MAX_QUEUE_LENGTH);
            if (!pushed) {
                log.error("命令下发失败(Redis 不可达): node={} type={}", nodeId, command.get("type"));
                return null;
            }
            log.info("命令已下发: node={} type={} id={}", nodeId, command.get("type"), commandId);
            return commandId;
        } catch (Exception e) {
            log.error("命令序列化失败: node={} type={}", nodeId, command.get("type"), e);
            return null;
        }
    }

    /**
     * 命令公共字段。字段名必须与 broker 侧的 {@code AdminCommand} 记录一致 ——
     * 这是两个工程之间又一处没有编译期检查的契约。
     */
    private Map<String, Object> base(String type) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("id", UUID.randomUUID().toString());
        command.put("type", type);
        command.put("issuedAt", System.currentTimeMillis());
        command.put("maxAgeMs", properties.commandMaxAgeMs());
        return command;
    }
}

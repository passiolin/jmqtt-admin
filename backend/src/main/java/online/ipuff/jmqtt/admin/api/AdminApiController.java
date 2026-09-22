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
package online.ipuff.jmqtt.admin.api;

import online.ipuff.jmqtt.admin.AdminProperties;
import online.ipuff.jmqtt.admin.command.NodeCommandService;
import online.ipuff.jmqtt.admin.query.ClusterQueryService;
import online.ipuff.jmqtt.admin.query.ClusterQueryService.ClientEntry;
import online.ipuff.jmqtt.admin.query.ClusterQueryService.ClientPage;
import online.ipuff.jmqtt.admin.query.ClusterQueryService.ClusterPage;
import online.ipuff.jmqtt.admin.query.ClusterQueryService.OverlapReport;
import online.ipuff.jmqtt.admin.query.ClusterQueryService.TopicEntry;
import online.ipuff.jmqtt.admin.query.ClusterQueryService.TopicPage;
import online.ipuff.jmqtt.admin.query.NodeView;
import online.ipuff.jmqtt.admin.redis.AdminRedis;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集群查询与单点操作。
 *
 * <p>查询全部读 Redis; 唯一的写操作是下发命令(踢下线 / 触发对账), 而命令也是写进 Redis 队列,
 * 由节点自己取走执行。控制台<b>没有</b>任何直接修改 broker 状态的途径。
 */
@RestController
@RequestMapping("/api")
public class AdminApiController {

    private final ClusterQueryService query;
    private final NodeCommandService commands;
    private final AdminRedis redis;
    private final AdminProperties properties;

    public AdminApiController(ClusterQueryService query, NodeCommandService commands,
                              AdminRedis redis, AdminProperties properties) {
        this.query = query;
        this.commands = commands;
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 总览。前端首屏用它, 并且<b>必须</b>根据 {@code redis.reachable} 决定是否显示
     * 「数据不可信」的横幅 —— 见 {@link AdminRedis} 的类注释。
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        List<NodeView> nodes = query.nodes();
        long totalConnections = 0;
        long totalSessions = 0;
        long online = 0;
        for (NodeView node : nodes) {
            if (node.online()) {
                online++;
                totalConnections += Math.max(0, node.connections());
                totalSessions += Math.max(0, node.sessions());
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        AdminRedis.Status status = redis.status();
        data.put("redis", Map.of(
                "reachable", status.reachable(),
                "error", status.error() == null ? "" : status.error(),
                "checkedAt", status.checkedAt()));
        data.put("nodes", nodes);
        data.put("totals", Map.of(
                "nodes", nodes.size(),
                "onlineNodes", online,
                "connections", totalConnections,
                "sessions", totalSessions));
        data.put("limits", Map.of(
                "maxEvictPerTask", properties.maxEvictPerTask(),
                "defaultEvictBatchSize", properties.defaultEvictBatchSize(),
                "defaultEvictIntervalMs", properties.defaultEvictIntervalMs(),
                "reconnectTimeoutSeconds", properties.reconnectTimeoutSeconds(),
                "precheckWindowMs", properties.precheckWindowMs(),
                "nodeStaleAfterMs", properties.nodeStaleAfterMs()));
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * 只返回 Redis 可达状态。
     *
     * <p>顶部状态灯每 15 秒轮询一次, 因此它不能走 {@link #overview()} ——
     * 那个接口要遍历所有节点。这里只读一个已经缓存的标记。
     */
    @GetMapping("/redis/status")
    public ResponseEntity<Map<String, Object>> redisStatus() {
        AdminRedis.Status status = redis.status();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reachable", status.reachable());
        data.put("error", status.error() == null ? "" : status.error());
        data.put("checkedAt", status.checkedAt());
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    @GetMapping("/nodes")
    public ResponseEntity<Map<String, Object>> nodes() {
        return ResponseEntity.ok(ApiResponse.ok(query.nodes()));
    }

    @GetMapping("/nodes/{node}")
    public ResponseEntity<Map<String, Object>> node(@PathVariable String node) {
        return ResponseEntity.ok(ApiResponse.ok(query.node(node)));
    }

    // ------------------------------------------------------------------
    // 客户端
    // ------------------------------------------------------------------

    /**
     * 单节点的客户端分页。
     *
     * @param cursor 上一页返回的游标; 首次传 0
     * @param count  本页条数
     * @param prefix clientId 前缀
     */
    @GetMapping("/nodes/{node}/clients")
    public ResponseEntity<Map<String, Object>> clients(
            @PathVariable String node,
            @RequestParam(defaultValue = "0") String cursor,
            @RequestParam(defaultValue = "100") int count,
            @RequestParam(required = false) String prefix) {
        ClientPage page = query.clients(node, cursor, count, prefix);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    /**
     * 跨节点客户端视图。
     *
     * <p>每节点各取 {@code limit} 条后合并 —— 结果里带着每节点的取数情况,
     * 前端必须展示它, 否则会被误当成「全集群前 limit 条」。
     */
    @GetMapping("/clients")
    public ResponseEntity<Map<String, Object>> clientsAcrossNodes(
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String prefix) {
        ClusterPage page = query.clientsAcrossNodes(limit, prefix);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @GetMapping("/nodes/{node}/clients/{clientId}")
    public ResponseEntity<Map<String, Object>> client(@PathVariable String node,
                                                     @PathVariable String clientId) {
        ClientEntry entry = query.client(node, clientId);
        if (entry == null) {
            return ResponseEntity.status(404).body(ApiResponse.fail("客户端不在线或不存在: " + clientId));
        }
        return ResponseEntity.ok(ApiResponse.ok(entry));
    }

    /**
     * 踢掉单个客户端。
     *
     * <p>注意这与排水是同一套动作, 只是规模为 1 —— 会话与订阅都不会被删除,
     * 客户端会立刻重连并恢复。
     */
    @PostMapping("/nodes/{node}/clients/{clientId}/kick")
    public ResponseEntity<Map<String, Object>> kick(@PathVariable String node,
                                                   @PathVariable String clientId,
                                                   @RequestBody(required = false) KickRequest request) {
        boolean publishWill = request == null || request.publishWill() == null || request.publishWill();
        String commandId = commands.kick(node, clientId, publishWill);
        if (commandId == null) {
            return ResponseEntity.status(503).body(ApiResponse.fail("命令下发失败: Redis 不可达"));
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of("commandId", commandId)));
    }

    /**
     * 查询命令结果/进度。排水与踢下线共用这一个入口 ——
     * 它们的区别只是命令类型, 结果形状是一致的。
     */
    @GetMapping("/commands/{commandId}")
    public ResponseEntity<Map<String, Object>> commandResult(@PathVariable String commandId) {
        Map<String, String> result = commands.result(commandId);
        if (result == null) {
            // 还没被节点取走。这不是错误, 前端据此显示「等待节点响应」
            return ResponseEntity.ok(ApiResponse.ok(Map.of("state", "PENDING")));
        }
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ------------------------------------------------------------------
    // 主题
    // ------------------------------------------------------------------

    @GetMapping("/nodes/{node}/topics")
    public ResponseEntity<Map<String, Object>> topics(
            @PathVariable String node,
            @RequestParam(defaultValue = "0") String cursor,
            @RequestParam(defaultValue = "100") int count,
            @RequestParam(required = false) String prefix,
            @RequestParam(defaultValue = "false") boolean sortByCount) {
        TopicPage page = query.topics(node, cursor, count, prefix, sortByCount);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @GetMapping("/topics")
    public ResponseEntity<Map<String, Object>> topicsAcrossNodes(
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String prefix,
            @RequestParam(defaultValue = "true") boolean sortByCount) {
        List<TopicEntry> entries = query.topicsAcrossNodes(limit, prefix, sortByCount);
        return ResponseEntity.ok(ApiResponse.ok(entries));
    }

    /**
     * 跨节点订阅重叠检测。
     *
     * <p>用途: 验证「关闭集群广播」这个决定是否安全。广播关掉之后, 若某个主题过滤器
     * 在多个节点上都有订阅者, 那些订阅者将永远收不到消息且没有任何报错 ——
     * 这个检查是唯一能提前发现它的手段。
     */
    @GetMapping("/topics/overlap")
    public ResponseEntity<Map<String, Object>> overlap(
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "20000") int maxScan) {
        OverlapReport report = query.overlap(limit, maxScan);
        return ResponseEntity.ok(ApiResponse.ok(report));
    }

    /**
     * 让节点立即刷新状态视图。控制台的数字明显不对时用它, 比等心跳快。
     */
    @PostMapping("/nodes/{node}/snapshot")
    public ResponseEntity<Map<String, Object>> snapshot(@PathVariable String node) {
        String commandId = commands.snapshot(node);
        if (commandId == null) {
            return ResponseEntity.status(503).body(ApiResponse.fail("命令下发失败: Redis 不可达"));
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of("commandId", commandId)));
    }

    /**
     * @param publishWill 是否按规范发布遗嘱; 省略视为 true
     */
    public record KickRequest(Boolean publishWill) {
    }
}

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

import online.ipuff.jmqtt.admin.drain.DrainService;
import online.ipuff.jmqtt.admin.drain.DrainSession;
import online.ipuff.jmqtt.admin.drain.DrainView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 驱逐流程接口。
 *
 * <h2>为什么是四个独立动作, 而不是一个「执行驱逐」的接口</h2>
 * 「停止负载均衡调度」这一步<b>不在控制台的权限范围内</b> —— 它可能是改 Nginx 权重、
 * 改 K8s 的 readiness、改云负载均衡的后端组, 各家的接口都不一样, 也通常需要另一套权限。
 * 控制台能做的是: 把这件事说清楚、在它没做好的时候给出证据、并且绝不在它没做之前就动手。
 *
 * <p>于是流程被拆成四个显式动作, 每个都能单独重试与观察:
 * <pre>
 *   POST /api/drains                 创建(并自动进入预检)
 *   POST /api/drains/{id}/confirm-lb 人工确认已停调度
 *   POST /api/drains/{id}/start      开始驱逐
 *   POST /api/drains/{id}/abort      中止
 * </pre>
 *
 * <p>把一个「点一下全干完」的接口做出来是很诱人的, 但它会把一个必须由人负责的步骤
 * (停调度)埋进一段自动化里 —— 而出问题时, 那段自动化不会告诉任何人它跳过了什么。
 */
@RestController
@RequestMapping("/api/drains")
public class DrainController {

    private final DrainService drainService;

    public DrainController(DrainService drainService) {
        this.drainService = drainService;
    }

    /**
     * 创建驱逐任务。返回后<b>立刻</b>开始预检(异步), 前端轮询 {@code /{id}} 看预检结果。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request) {
        try {
            DrainSession session = drainService.create(
                    request.node(),
                    request.mode() == null ? "ratio" : request.mode(),
                    request.value() == null ? 1.0 : request.value(),
                    request.batchSize(),
                    request.intervalMs(),
                    request.publishWill() == null || request.publishWill(),
                    request.clientIdPrefix());
            return ResponseEntity.ok(ApiResponse.ok(DrainView.of(session)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.fail(e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        List<DrainView> views = drainService.list().stream().map(DrainView::of).toList();
        return ResponseEntity.ok(ApiResponse.ok(views));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        DrainSession session = drainService.get(id);
        if (session == null) {
            return ResponseEntity.status(404).body(ApiResponse.fail("找不到驱逐任务: " + id));
        }
        return ResponseEntity.ok(ApiResponse.ok(DrainView.of(session)));
    }

    /**
     * 人工确认「已停止向该节点调度」。
     *
     * @param request {@code override=true} 时忽略预检告警强行继续。需要显式传,
     *                因为它是唯一一个「明知可能做错仍然继续」的入口
     */
    @PostMapping("/{id}/confirm-lb")
    public ResponseEntity<Map<String, Object>> confirmLb(@PathVariable String id,
                                                        @RequestBody(required = false) ConfirmRequest request) {
        try {
            drainService.confirmLb(id, request != null && Boolean.TRUE.equals(request.override()));
            return ResponseEntity.ok(ApiResponse.ok(DrainView.of(drainService.get(id))));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.fail(e.getMessage()));
        }
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable String id) {
        try {
            drainService.start(id);
            return ResponseEntity.ok(ApiResponse.ok(DrainView.of(drainService.get(id))));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.fail(e.getMessage()));
        }
    }

    @PostMapping("/{id}/abort")
    public ResponseEntity<Map<String, Object>> abort(@PathVariable String id) {
        try {
            drainService.abort(id);
            return ResponseEntity.ok(ApiResponse.ok(DrainView.of(drainService.get(id))));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.fail(e.getMessage()));
        }
    }

    /**
     * @param node           目标节点
     * @param mode           {@code ratio}(按比例) 或 {@code count}(按条数)
     * @param value          0~1 的比例, 或条数
     * @param batchSize      每批条数; 省略用默认值
     * @param intervalMs     批次间隔; 省略用默认值
     * @param publishWill    是否按规范发布遗嘱; 省略视为 true
     * @param clientIdPrefix 只驱逐匹配该前缀的客户端; 省略表示不筛选。
     *                       用途是灰度: 先拿一小批设备验证整条链路, 再全量做
     */
    public record CreateRequest(String node, String mode, Double value, Integer batchSize,
                                Integer intervalMs, Boolean publishWill, String clientIdPrefix) {
    }

    /**
     * @param override 是否忽略预检告警
     */
    public record ConfirmRequest(Boolean override) {
    }
}

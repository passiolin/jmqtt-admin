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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一响应体, 与 broker 的 {@code /open/api/jmqtt/*} 保持同一种形状。
 *
 * <p>两个工程共用一种形状的价值在于: 前端只有一套解析逻辑, 排查时也不必先想
 * 「这个接口的成功标志是 {@code code} 还是 {@code success}」。
 */
public final class ApiResponse {

    private ApiResponse() {
    }

    public static Map<String, Object> ok(Object data) {
        return body(true, null, data);
    }

    public static Map<String, Object> ok() {
        return body(true, null, null);
    }

    public static Map<String, Object> fail(String message) {
        return body(false, message, null);
    }

    public static Map<String, Object> body(boolean success, String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", success);
        if (message != null) {
            body.put("message", message);
        }
        if (data != null) {
            body.put("data", data);
        }
        return body;
    }
}

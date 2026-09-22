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

import online.ipuff.jmqtt.admin.auth.AuthInterceptor;
import online.ipuff.jmqtt.admin.auth.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录相关接口。
 *
 * <p>账号密码来自配置文件({@code jmqtt.admin.username / password})。
 * 这只适用于内网管理台: 它没有密码强度策略、没有失败锁定、没有审计留痕。
 * 若要暴露到更大范围, 应当改为接企业统一登录 —— 那是一个独立的课题,
 * 而不是给这里再加几个字段能解决的。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final TokenService tokenService;

    public AuthController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request) {
        if (request.username() == null || request.password() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("用户名与密码不能为空"));
        }
        String token = tokenService.login(request.username(), request.password());
        if (token == null) {
            // 不区分「用户名不存在」与「密码错误」: 区分会给撞库提供用户名枚举
            return ResponseEntity.status(401).body(ApiResponse.fail("用户名或密码错误"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("username", request.username());
        data.put("expiresAt", tokenService.expiresAt(token));
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Admin-Token", required = false) String customToken) {
        tokenService.logout(extract(authorization, customToken));
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        Object username = request.getAttribute(AuthInterceptor.ATTR_USERNAME);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", username);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    private static String extract(String authorization, String customToken) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        return customToken;
    }

    /**
     * @param username 登录用户名
     * @param password 登录密码
     */
    public record LoginRequest(String username, String password) {
    }
}

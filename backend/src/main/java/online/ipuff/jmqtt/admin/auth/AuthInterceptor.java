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
package online.ipuff.jmqtt.admin.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 登录校验。
 *
 * <p>白名单只有一条: 登录接口本身。其余 {@code /api/**} 一律要求令牌 ——
 * 用「默认全部保护、显式放行」而不是反过来, 是因为后者的错误方式是
 * 「新加了一个接口, 忘了加保护」, 而那种疏漏往往几个月都不会被发现。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 请求属性名: 当前登录用户 */
    public static final String ATTR_USERNAME = "jmqtt.admin.username";

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;

    public AuthInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        // 预检请求不带自定义头, 拦下来会让浏览器认为跨域失败
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String token = extractToken(request);
        String username = tokenService.usernameOf(token);
        if (username == null) {
            writeUnauthorized(response);
            return false;
        }
        request.setAttribute(ATTR_USERNAME, username);
        return true;
    }

    /**
     * 从请求里取令牌。
     *
     * <p>同时接受标准 {@code Authorization: Bearer} 与自定义头 ——
     * 前者便于用 curl 之类通用工具调试, 后者便于在前端里避开某些中间件对
     * {@code Authorization} 的特殊处理。
     */
    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return request.getHeader("X-Admin-Token");
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"未登录或登录已过期\"}");
    }
}

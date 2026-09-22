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
package online.ipuff.jmqtt.admin.config;

import online.ipuff.jmqtt.admin.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层装配。
 *
 * <p>只注册一个拦截器, 不配置静态资源映射 —— 前端构建产物由 Spring Boot 的
 * 默认静态资源处理(从 classpath:/static/ 提供)负责, 不需要额外代码。
 * 前端用 hash 路由, 因此也不需要 SPA 的 history fallback 转发规则。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                // 保护全部 /api/**, 只放行登录
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login");
    }
}

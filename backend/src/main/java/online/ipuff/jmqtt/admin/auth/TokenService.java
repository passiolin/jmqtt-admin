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

import online.ipuff.jmqtt.admin.AdminProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录令牌。
 *
 * <h2>为什么是「内存令牌」而不是会话或 JWT</h2>
 * <ul>
 *   <li><b>不用 HttpSession</b>: 会话依赖容器内存与 cookie 语义, 在反向代理后面
 *       经常需要额外的配置才正确(secure / sameSite / 路径), 而这些配置错了只会
 *       表现为「登录后立刻又是登录页」, 排查成本远高于收益。</li>
 *   <li><b>不用 JWT</b>: 控制台是<b>单实例</b>且必须能立即踢出登录态。
 *       JWT 的无状态特性在这里只带来一个坏处 —— 令牌签发后在过期前无法撤销。</li>
 * </ul>
 *
 * <p>代价是控制台重启会让所有人重新登录。对管理台而言这可以接受, 甚至更好:
 * 重启即撤销所有旧令牌。
 *
 * <h2>令牌本身</h2>
 * 32 字节 {@link SecureRandom} 的 Base64 编码。只是随机数, <b>不承载任何信息</b> ——
 * 用户身份存在这张表里。这样令牌被猜到或篡改的可能性只取决于随机源质量,
 * 而与「编码了哪些字段」无关。
 */
@Component
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    private final AdminProperties properties;

    /**
     * @param username  归属用户
     * @param expiresAt 过期时间(epoch millis)
     */
    private record Entry(String username, long expiresAt) {
    }

    public TokenService(AdminProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验账号密码并签发令牌。
     *
     * <p>用 {@link MessageDigest#isEqual} 做常量时间比较: 普通的 {@code String.equals}
     * 在首个不同字节处就返回, 比较耗时与「猜中了前几个字符」相关 ——
     * 这足以让攻击者逐字节地把密码试出来。管理台虽然在内网, 但这条约束的成本是零。
     *
     * @return 令牌; 账号或密码错误时返回 null
     */
    public String login(String username, String password) {
        if (!constantTimeEquals(username, properties.username())
                | !constantTimeEquals(password, properties.password())) {
            log.warn("登录失败, 用户名: {}", username);
            return null;
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, new Entry(username, System.currentTimeMillis()
                + properties.tokenTtlSeconds() * 1000L));
        log.info("登录成功: {}", username);
        return token;
    }

    /**
     * 解析令牌归属用户。
     *
     * @return 用户名; 令牌不存在或已过期时返回 null
     */
    public String usernameOf(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Entry entry = tokens.get(token);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt() < System.currentTimeMillis()) {
            tokens.remove(token, entry);
            return null;
        }
        return entry.username();
    }

    public void logout(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    public long expiresAt(String token) {
        Entry entry = tokens.get(token);
        return entry == null ? 0L : entry.expiresAt();
    }

    /**
     * 清理过期令牌。不清理也能工作(查询时会顺手判过期), 但长期不重启会慢慢积压。
     */
    @Scheduled(fixedDelay = 600_000)
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        int before = tokens.size();
        tokens.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        int removed = before - tokens.size();
        if (removed > 0) {
            log.debug("已清理 {} 个过期登录令牌", removed);
        }
    }

    /**
     * 常量时间比较。长度不同也走完整流程, 不提前返回。
     */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] left = (a == null ? "" : a).getBytes(StandardCharsets.UTF_8);
        byte[] right = (b == null ? "" : b).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }

    /**
     * 用于日志与诊断的令牌指纹 —— <b>绝不打印令牌本身</b>。
     */
    public static String fingerprint(String token) {
        if (token == null) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (Exception e) {
            return "?";
        }
    }
}

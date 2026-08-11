package com.intelligent.agent.web.controller;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 当前请求用户 ID 提取：由 {@code JwtAuthFilter} 在鉴权后写入 request attribute
 * {@code userId}（JWT sub）。白名单/无 JWT 路径回退 "default"。
 */
public final class UserContext {

    private UserContext() {
    }

    public static String userId(HttpServletRequest req) {
        Object userId = req == null ? null : req.getAttribute("userId");
        return userId == null || userId.toString().isBlank() ? "default" : userId.toString();
    }
}

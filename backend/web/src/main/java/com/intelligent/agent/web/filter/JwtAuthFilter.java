package com.intelligent.agent.web.filter;

import com.intelligent.agent.web.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 描述：
 *
 * @author lin miao
 * @date 2026/5/11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    // 不需要鉴权的路径前缀
    // /ws WebSocket 升级请求由 JwtHandshakeInterceptor 在握手阶段鉴权
    private static final List<String> WHITE_LIST = Arrays.asList(
            // Auth & health
            "/api/auth/login",
            "/api/auth/logout",
            "/api/health",
            "/ws",
            // 前端静态资源（生产模式由 Java 直接提供）
            "/index.html",
            "/assets/",     // Vite 打包产物（JS/CSS/字体等）
            "/favicon.ico",
            "/favicon.svg",
            // Vue Router 前端路由（SPA 入口，均返回 index.html）
            "/",
            "/login",
            "/chat",
            "/tools",
            "/skills",
            "/tasks",
            "/memory",
            "/system",
            "/stats"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // 白名单直接放行
        if (WHITE_LIST.stream().anyMatch(path::startsWith)) {
            chain.doFilter(request, response);
            return;
        }

        // 普通请求从 Header 取 token
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            sendUnauthorized(response, "缺少 Authorization 头");
            return;
        }

        String token = header.substring(7);
        Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (Exception e) {
            sendUnauthorized(response, "token 无效或已过期");
            return;
        }

        // 滑动续期：快过期时在响应头带新 token
        if (jwtUtil.shouldRefresh(claims)) {
            String newToken = jwtUtil.generateToken(claims.getSubject());
            response.setHeader("X-New-Token", newToken);
        }

        chain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletResponse response, String message)
            throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                String.format("{\"success\":false,\"message\":\"%s\"}", message)
        );
    }
}
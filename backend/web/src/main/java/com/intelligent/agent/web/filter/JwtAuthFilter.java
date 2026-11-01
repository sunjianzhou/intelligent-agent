package com.intelligent.agent.web.filter;

import com.intelligent.agent.web.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
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
            "/api/auth/cli-token",        // CLI scoped token 换取（用户名+密码换 token，无需已有 token）
            "/api/health",
            "/feishu/event",              // 飞书 Webhook 事件接收端点（外部回调，无 JWT）
            "/feishu/callback/interactive", // 飞书卡片回调，由 FeishuCrypto.verifyEventSignature 鉴权
            "/feishu/oauth/callback",     // 飞书 OAuth 回调（用户浏览器重定向，无 JWT）
            "/wecom/callback",            // 企业微信回调（由 WeComCrypto.verifySignature 鉴权）
            "/ws"
            // 说明：SPA 静态资源与前端路由由 frontend 容器（nginx）提供，
            // 后端不打包前端产物，因此这里不再放行 /index.html、/assets/、
            // /login、/chat 等前端路径（TD-16，2026-11-01）。
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // 白名单直接放行（前缀匹配；"/" 不再放行——后端不提供 SPA 入口页）
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
        // 供下游控制器直接读取真实用户 ID（替代已退役 Python 代理的 X-User-Id 解析）
        request.setAttribute("userId", claims.getSubject());

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

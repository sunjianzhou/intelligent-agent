package com.intelligent.agent.web.config;

import com.intelligent.agent.web.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 在 WebSocket 握手（HTTP Upgrade）阶段验证 JWT，
 * 非法 token 在连接建立前直接拒绝，不泄露任何系统信息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes)
            throws Exception {
        if (!(request instanceof ServletServerHttpRequest)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String token = ((ServletServerHttpRequest) request).getServletRequest().getParameter("token");
        if (token == null || token.isEmpty()) {
            log.warn("WebSocket 握手拒绝：缺少 token，来源: {}", request.getRemoteAddress());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        try {
            io.jsonwebtoken.Claims claims = jwtUtil.parse(token);
            String userId = claims.getSubject();
            if (userId != null) {
                attributes.put("userId", userId);
            }
            return true;
        } catch (Exception e) {
            log.warn("WebSocket 握手拒绝：token 无效，来源: {}", request.getRemoteAddress());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手成功后无需额外处理
    }
}

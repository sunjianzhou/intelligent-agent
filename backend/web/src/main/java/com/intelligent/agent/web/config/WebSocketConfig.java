package com.intelligent.agent.web.config;

import com.intelligent.agent.web.controller.WebSocketController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

/**
 * 描述：
 *
 * @author lin miao
 * @date 2026/5/1
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSocketController webSocketController;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Value("${cors.allowed-origins:*}")
    private String corsAllowedOrigins;

    @Autowired
    public WebSocketConfig(WebSocketController webSocketController,
                           JwtHandshakeInterceptor jwtHandshakeInterceptor) {
        this.webSocketController = webSocketController;
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = corsAllowedOrigins.split(",");
        registry.addHandler(webSocketController, "/ws")
                .setAllowedOriginPatterns(origins)
                .addInterceptors(new HttpSessionHandshakeInterceptor(), jwtHandshakeInterceptor);
    }
}

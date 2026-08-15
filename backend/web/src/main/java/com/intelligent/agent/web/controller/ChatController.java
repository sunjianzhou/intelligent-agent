package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.dto.response.ApiResponse;
import com.intelligent.agent.web.api.chat.LocalChatService;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.service.AgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;

/**
 * 聊天端点（Java-only）：REST 非流式 + SSE 流式（CLI 契约 /api/chat/stream）。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    private final AgentService agentService;
    private final LocalChatService localChatService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ChatController(AgentService agentService,
                          LocalChatService localChatService,
                          ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.localChatService = localChatService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<HashMap>> chat(@Valid @RequestBody ChatRequest request,
                                                     HttpServletRequest httpRequest) {
        // 真实用户 ID 一律取 JWT（JwtAuthFilter 写入 request attribute），
        // 不信任请求体/客户端传入的身份（REST 路径此前 userId 恒为 null → 所有用户共享 "default" 记忆）。
        request.setUserId(UserContext.userId(httpRequest));
        log.info("收到REST聊天请求: {}", request.getMessage());
        try {
            long startTime = System.currentTimeMillis();
            Map<String, Object> chatResult = agentService.chatFull(request);
            String response = String.valueOf(chatResult.getOrDefault("response", "服务异常"));
            long endTime = System.currentTimeMillis();

            double responseTime = (endTime - startTime) / 1000.0;

            HashMap<String, Object> data = new HashMap<>(10);
            data.put("status", "success");
            data.put("response", response);
            // 2026-08-15：透传会话 message_id（撤回级联/前端消息同步依赖）
            if (chatResult.get("user_message_id") != null) {
                data.put("user_message_id", chatResult.get("user_message_id"));
            }
            if (chatResult.get("assistant_message_id") != null) {
                data.put("assistant_message_id", chatResult.get("assistant_message_id"));
            }
            if (chatResult.get("tool_calls") != null) {
                data.put("tool_calls", chatResult.get("tool_calls"));
            }
            data.put("response_time", responseTime);
            data.put("timestamp", System.currentTimeMillis());

            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("聊天请求处理失败", e);
            HashMap<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "处理聊天请求失败: " + e.getMessage());
            error.put("timestamp", System.currentTimeMillis());

            return ResponseEntity.ok(ApiResponse.error("聊天失败: " + e.getMessage(), error));
        }
    }

    /** SSE 流式聊天（CLI 契约 /api/chat/stream）。 */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequest request,
                                                HttpServletRequest httpRequest) {
        request.setUserId(UserContext.userId(httpRequest));
        return localChatService.stream(request)
                .map(event -> ServerSentEvent.<String>builder()
                        .event(event.type())
                        .data(json(event))
                        .build())
                .onErrorResume(e -> Flux.just(ServerSentEvent.builder("event")
                        .data("{\"type\":\"error\",\"data\":\"" + escape(e.getMessage()) + "\"}")
                        .build()));
    }

    private String json(ModelEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"data\":\"serialize failed\"}";
        }
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\"", "'");
    }
}

package com.intelligent.agent.web.controller;
import lombok.extern.slf4j.Slf4j;

import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.dto.response.ApiResponse;
import com.intelligent.agent.web.service.AgentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;

/**
 * 描述：
 *
 * @author lin miao
 * @date 2026/5/1
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    private final AgentService agentService;

    public ChatController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<HashMap>> chat(@Valid @RequestBody ChatRequest request) {
        log.info("收到REST聊天请求: {}", request.getMessage());

        try {
            long startTime = System.currentTimeMillis();
            String response = agentService.chat(request);
            long endTime = System.currentTimeMillis();

            double responseTime = (endTime - startTime) / 1000.0;

            HashMap<String, Object> data = new HashMap<>(10);
            data.put("status", "success");
            data.put("response", response);
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
}

package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 对话历史代理端点（转发到 Python Agent /api/conversations/*）。
 */
@Slf4j
@RestController
public class ConversationsProxyController extends AbstractProxyController {

    @GetMapping("/api/conversations")
    public ResponseEntity<Map<String, Object>> listConversations(HttpServletRequest req) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("success", false);
        fallback.put("sessions", Collections.emptyList());
        return proxyGetAbsolute(proxy.getBaseUrl() + "/api/conversations", req, fallback);
    }

    @GetMapping("/api/conversations/{sessionId}")
    public ResponseEntity<Map<String, Object>> getConversation(
            @PathVariable String sessionId, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get(
                    proxy.getBaseUrl() + "/api/conversations/" + sessionId, true, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            if (res.getStatusCode().value() == 404)
                return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("获取对话历史失败: {}", sessionId, e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "获取对话失败");
        return ResponseEntity.ok(err);
    }

    @DeleteMapping("/api/conversations/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteConversation(
            @PathVariable String sessionId, HttpServletRequest req) {
        return proxyDelete("/api/conversations/" + sessionId, req);
    }

    @DeleteMapping("/api/conversations")
    public ResponseEntity<Map<String, Object>> clearAllConversations(HttpServletRequest req) {
        return proxyDelete("/api/conversations", req);
    }
}

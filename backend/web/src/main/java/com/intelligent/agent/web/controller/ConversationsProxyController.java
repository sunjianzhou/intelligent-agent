package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.domain.InvalidRequestException;
import com.intelligent.agent.web.domain.NotFoundException;
import com.intelligent.agent.web.domain.conversation.ConversationService;
import com.intelligent.agent.web.feishu.FeishuRecallBridge;
import com.intelligent.agent.web.service.PythonProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话历史端点。
 * <ul>
 *   <li>java / shadow 运行时：走本地 {@link ConversationService}（JSON 文件持久化）；</li>
 *   <li>python 运行时：转发到 Python Agent /api/conversations/*（向后兼容）。</li>
 * </ul>
 */
@Slf4j
@RestController
public class ConversationsProxyController {

    private final PythonProxyService proxy;
    private final ObjectMapper objectMapper;
    private final ConversationService conversationService;
    private final String runtimeMode;
    private final FeishuRecallBridge feishuRecallBridge;

    public ConversationsProxyController(PythonProxyService proxy,
                                        ObjectMapper objectMapper,
                                        ConversationService conversationService,
                                        @Value("${ai.runtime.mode:python}") String runtimeMode,
                                        FeishuRecallBridge feishuRecallBridge) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
        this.conversationService = conversationService;
        this.runtimeMode = runtimeMode;
        this.feishuRecallBridge = feishuRecallBridge;
    }

    @GetMapping("/api/conversations")
    public ResponseEntity<Map<String, Object>> listConversations(HttpServletRequest req) {
        if (localRuntime()) {
            return ok(conversationService.listConversations(userId(req)));
        }
        return proxyGet("/api/conversations", req);
    }

    @GetMapping("/api/conversations/{sessionId}")
    public ResponseEntity<Map<String, Object>> getConversation(
            @PathVariable String sessionId, HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> conversationService.getConversation(userId(req), sessionId));
        }
        return proxyGet("/api/conversations/" + sessionId, req);
    }

    @DeleteMapping("/api/conversations/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteConversation(
            @PathVariable String sessionId, HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> conversationService.deleteConversation(userId(req), sessionId));
        }
        return proxyDelete("/api/conversations/" + sessionId, req);
    }

    @DeleteMapping("/api/conversations")
    public ResponseEntity<Map<String, Object>> clearAllConversations(HttpServletRequest req) {
        if (localRuntime()) {
            return ok(conversationService.clearAllConversations(userId(req)));
        }
        return proxyDelete("/api/conversations", req);
    }

    @PostMapping("/api/conversations/append")
    public ResponseEntity<Map<String, Object>> appendConversation(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (localRuntime()) {
            return ok(conversationService.append(userId(req),
                    str(body.get("session_id")), messagesOf(body.get("messages"))));
        }
        return proxyPost("/api/conversations/append", body, req);
    }

    @PostMapping("/api/conversations/branch")
    public ResponseEntity<Map<String, Object>> branchConversation(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (localRuntime()) {
            return guarded(() -> conversationService.branchConversation(userId(req), body));
        }
        return proxyPost("/api/conversations/branch", body, req);
    }

    @PostMapping("/api/conversations/{sessionId}/retract")
    public ResponseEntity<Map<String, Object>> retractMessages(
            @PathVariable String sessionId, @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> resp;
        if (localRuntime()) {
            resp = guarded(() -> conversationService.retract(userId(req), sessionId,
                    stringList(body == null ? null : body.get("message_ids"))));
        } else {
            resp = proxyPost("/api/conversations/" + sessionId + "/retract", body, req);
        }
        if (feishuRecallBridge != null) {
            feishuRecallBridge.onMessagesRetracted(resp.getBody());  // 失败不影响本次响应
        }
        return resp;
    }

    // ── 辅助 ──────────────────────────────────────────────────

    private boolean localRuntime() {
        return "java".equals(runtimeMode) || "shadow".equals(runtimeMode);
    }

    private String userId(HttpServletRequest req) {
        if (proxy != null) {
            String userId = proxy.extractUserIdFromRequest(req);
            if (userId != null) {
                return userId;
            }
        }
        return "default";
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messagesOf(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        return ((List<?>) value).stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        return ((List<?>) value).stream().map(String::valueOf).toList();
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> guarded(
            java.util.function.Supplier<Map<String, Object>> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (NotFoundException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        } catch (InvalidRequestException e) {
            return ResponseEntity.status(400).body(error(e.getMessage()));
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }

    // ── Python 代理回退 ───────────────────────────────────────

    private ResponseEntity<Map<String, Object>> proxyGet(String path, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get(path, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
            if (res.getStatusCode().value() == 404) {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("GET {} 失败", path, e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "获取对话失败");
        return ResponseEntity.ok(err);
    }

    private ResponseEntity<Map<String, Object>> proxyPost(String path, Object body, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post(path, body, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("POST {} 失败", path, e);
        }
        return ResponseEntity.ok(errResponse());
    }

    private ResponseEntity<Map<String, Object>> proxyDelete(String path, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.delete(path, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
            if (res.getStatusCode().value() == 404) {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("DELETE {} 失败", path, e);
        }
        return ResponseEntity.ok(errResponse());
    }

    private static Map<String, Object> errResponse() {
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return err;
    }
}

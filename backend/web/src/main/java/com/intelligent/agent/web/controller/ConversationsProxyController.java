package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.domain.InvalidRequestException;
import com.intelligent.agent.web.domain.NotFoundException;
import com.intelligent.agent.web.domain.conversation.ConversationService;
import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.feishu.FeishuRecallBridge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话历史端点（本地 {@link ConversationService}，JSON 文件持久化）。
 */
@Slf4j
@RestController
public class ConversationsProxyController {

    private final ConversationService conversationService;
    private final FeishuRecallBridge feishuRecallBridge;
    private final ConversationMemoryService memoryService;

    public ConversationsProxyController(ConversationService conversationService,
                                        FeishuRecallBridge feishuRecallBridge) {
        this(conversationService, feishuRecallBridge, null);
    }

    @Autowired
    public ConversationsProxyController(ConversationService conversationService,
                                        FeishuRecallBridge feishuRecallBridge,
                                        ConversationMemoryService memoryService) {
        this.conversationService = conversationService;
        this.feishuRecallBridge = feishuRecallBridge;
        this.memoryService = memoryService;
    }

    @GetMapping("/api/conversations")
    public ResponseEntity<Map<String, Object>> listConversations(HttpServletRequest req) {
        return ok(conversationService.listConversations(UserContext.userId(req)));
    }

    @GetMapping("/api/conversations/{sessionId}")
    public ResponseEntity<Map<String, Object>> getConversation(
            @PathVariable String sessionId, HttpServletRequest req) {
        return guarded(() -> conversationService.getConversation(
                UserContext.userId(req), sessionId));
    }

    @DeleteMapping("/api/conversations/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteConversation(
            @PathVariable String sessionId, HttpServletRequest req) {
        return guarded(() -> conversationService.deleteConversation(
                UserContext.userId(req), sessionId));
    }

    @DeleteMapping("/api/conversations")
    public ResponseEntity<Map<String, Object>> clearAllConversations(HttpServletRequest req) {
        return ok(conversationService.clearAllConversations(UserContext.userId(req)));
    }

    @PostMapping("/api/conversations/append")
    public ResponseEntity<Map<String, Object>> appendConversation(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return ok(conversationService.append(UserContext.userId(req),
                str(body.get("session_id")), messagesOf(body.get("messages"))));
    }

    @PostMapping("/api/conversations/branch")
    public ResponseEntity<Map<String, Object>> branchConversation(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return guarded(() -> conversationService.branchConversation(
                UserContext.userId(req), body));
    }

    @PostMapping("/api/conversations/{sessionId}/retract")
    public ResponseEntity<Map<String, Object>> retractMessages(
            @PathVariable String sessionId, @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> resp = guarded(() -> {
            Map<String, Object> result = conversationService.retract(
                    UserContext.userId(req), sessionId,
                    stringList(body == null ? null : body.get("message_ids")));
            cascadePurge(UserContext.userId(req), result);
            return result;
        });
        if (feishuRecallBridge != null) {
            feishuRecallBridge.onMessagesRetracted(resp.getBody());  // 失败不影响本次响应
        }
        return resp;
    }

    /** 撤回级联：短期记忆按内容删除 + 长期检索排除（Task 4.5）。 */
    @SuppressWarnings("unchecked")
    private void cascadePurge(String userId, Map<String, Object> result) {
        if (memoryService == null) {
            return;
        }
        Object contentsObj = result.get("removed_contents");
        if (!(contentsObj instanceof List)) {
            return;
        }
        List<String> contents = ((List<Object>) contentsObj).stream()
                .map(String::valueOf)
                .toList();
        int purged = memoryService.purgeMessages(userId, contents);
        if (purged > 0) {
            result.put("memory_purged", purged);
        }
    }

    // ── 辅助 ──────────────────────────────────────────────────

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messagesOf(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        // ConversationService.append 会原地补 id/timestamp，需返回可变列表
        return ((List<?>) value).stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<String, Object>) item)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
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
}

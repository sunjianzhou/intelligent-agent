package com.intelligent.agent.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.api.chat.LocalChatService;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.dto.WebSocketMessageType;
import com.intelligent.agent.web.domain.conversation.ConversationService;
import com.intelligent.agent.web.ai.agent.ActiveChatLimiter;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 聊天编排服务（Java-only，Python Agent 已于 2026-08-08 退役）：
 * 非流式 REST / 飞书接入走 {@link LocalChatService}，WebSocket 流式把本地
 * {@code ModelEvent} 映射为 WS 消息协议事件。
 */
@Slf4j
@Service
public class AgentService {

    private final ObjectMapper objectMapper;
    private final ExecutorService streamExecutor;
    private final LocalChatService localChatService;
    private final ModelService modelService;
    private final ToolExecutor toolExecutor;
    private final ConversationService conversationService;
    private final ActiveChatLimiter activeChatLimiter;

    public AgentService(ObjectMapper objectMapper,
                        @Qualifier("streamExecutor") ExecutorService streamExecutor,
                        LocalChatService localChatService) {
        this(objectMapper, streamExecutor, localChatService, null, null, null, null);
    }

    /** 兼容构造：limiter 为 null（测试/非 Spring 装配路径）。 */
    public AgentService(ObjectMapper objectMapper,
                        @Qualifier("streamExecutor") ExecutorService streamExecutor,
                        LocalChatService localChatService,
                        ModelService modelService,
                        ToolExecutor toolExecutor,
                        ConversationService conversationService) {
        this(objectMapper, streamExecutor, localChatService, modelService, toolExecutor,
                conversationService, null);
    }

    @Autowired
    public AgentService(ObjectMapper objectMapper,
                        @Qualifier("streamExecutor") ExecutorService streamExecutor,
                        LocalChatService localChatService,
                        ModelService modelService,
                        ToolExecutor toolExecutor,
                        ConversationService conversationService,
                        ActiveChatLimiter activeChatLimiter) {
        this.objectMapper = objectMapper;
        this.streamExecutor = streamExecutor;
        this.localChatService = localChatService;
        this.modelService = modelService;
        this.toolExecutor = toolExecutor;
        this.conversationService = conversationService;
        this.activeChatLimiter = activeChatLimiter;
    }

    // ── 非流式（ChatController / 飞书接入用）──────────────────

    public String chat(ChatRequest request) {
        Map<String, Object> result = chatFull(request);
        return result.getOrDefault("response", "服务异常").toString();
    }

    public Map<String, Object> chatFull(ChatRequest request) {
        return localChatFull(request);
    }

    // ── 流式（WebSocket 用）────────────────────────────────────

    public void streamChatAsync(ChatRequest request, WebSocketSession session,
                                String requestId, long startTime) {
        if (activeChatLimiter != null && !activeChatLimiter.tryAcquire()) {
            log.warn("流式对话并发已达上限，拒绝请求: {}", requestId);
            sendBusy(session, requestId);
            return;
        }
        try {
            streamExecutor.submit(() ->
                    localStreamChat(request, session, requestId, startTime));
        } catch (RejectedExecutionException e) {
            if (activeChatLimiter != null) {
                activeChatLimiter.release();
            }
            log.warn("流式线程池已满，拒绝请求: {}", requestId);
            sendBusy(session, requestId);
        }
    }

    private void sendBusy(WebSocketSession session, String requestId) {
        try {
            Map<String, Object> busyMsg = new HashMap<>();
            busyMsg.put("type", WebSocketMessageType.ERROR);
            busyMsg.put("message", "服务繁忙，请稍后再试");
            busyMsg.put("request_id", requestId);
            busyMsg.put("timestamp", LocalDateTime.now().toString());
            JsonUtil.sendJsonMessageQuiet(session, busyMsg);
        } catch (Exception ignored) {
            // best effort
        }
    }

    private void releaseChatSlot() {
        if (activeChatLimiter != null) {
            activeChatLimiter.release();
        }
    }

    private Map<String, Object> localChatFull(ChatRequest request) {
        try {
            String response = localChatService.complete(request).block(Duration.ofSeconds(620));
            Map<String, Object> result = new HashMap<>();
            result.put("response",   response == null ? "服务异常" : response);
            result.put("tool_calls", Collections.emptyList());
            // 2026-08-15 补齐：会话持久化 + message_id（对齐 Python chat_router，
            // 撤回级联依赖这些 id）
            Map<String, String> ids = persistTurn(request, response);
            if (ids != null) {
                result.put("user_message_id", ids.get("user"));
                result.put("assistant_message_id", ids.get("assistant"));
            }
            log.info("本地聊天服务响应成功");
            return result;
        } catch (Exception e) {
            log.error("调用本地聊天服务失败", e);
            Map<String, Object> err = new HashMap<>();
            err.put("response",   "智能体服务暂时不可用: " + e.getMessage());
            err.put("tool_calls", Collections.emptyList());
            return err;
        }
    }

    private void localStreamChat(ChatRequest request, WebSocketSession session,
                                 String requestId, long startTime) {
        StringBuilder fullMsg      = new StringBuilder();
        int[]         toolCallCount = {0};
        boolean[]     chatDoneEmitted = {false};
        try {
            localChatService.stream(request).subscribe(
                    event -> {
                        if (!session.isOpen()) {
                            return;
                        }
                        Map<String, Object> wsMsg = toWsMessage(
                                event.type(), event.data(), requestId,
                                fullMsg, toolCallCount, startTime);
                        if (wsMsg == null) {
                            return;
                        }
                        if (WebSocketMessageType.CHAT_DONE.equals(wsMsg.get("type"))) {
                            chatDoneEmitted[0] = true;
                            Map<String, String> ids = persistTurn(request, fullMsg.toString());
                            if (ids != null) {
                                wsMsg.put("user_message_id", ids.get("user"));
                                wsMsg.put("assistant_message_id", ids.get("assistant"));
                            }
                        }
                        if (isQuietWsEvent(event.type())) {
                            JsonUtil.sendJsonMessageQuiet(session, wsMsg);
                        } else {
                            JsonUtil.sendJsonMessage(session, wsMsg);
                        }
                    },
                    err -> {
                        log.error("本地流式聊天异常, requestId: {}", requestId, err);
                        try {
                            Map<String, Object> errMsg = new HashMap<>();
                            errMsg.put("type",       WebSocketMessageType.ERROR);
                            errMsg.put("message",    "流式聊天失败: " + err.getMessage());
                            errMsg.put("request_id", requestId);
                            JsonUtil.sendJsonMessageQuiet(session, errMsg);
                        } catch (Exception ignored) {}
                        releaseChatSlot();
                    },
                    () -> {
                        if (!chatDoneEmitted[0]) {
                            sendFallbackDone(session, requestId, startTime);
                        }
                        releaseChatSlot();
                    });
        } catch (Exception e) {
            log.error("本地流式聊天启动失败, requestId: {}", requestId, e);
            releaseChatSlot();
            sendFallbackDone(session, requestId, startTime);
        }
    }

    /** 将本地 {@code ModelEvent} 流映射为 WebSocket 消息协议。未知类型返回 null。 */
    private Map<String, Object> toWsMessage(String eventType, Object eventData,
                                            String requestId, StringBuilder fullMsg,
                                            int[] toolCallCount, long startTime) {
        Map<String, Object> wsMsg = new HashMap<>();
        wsMsg.put("request_id", requestId);
        wsMsg.put("timestamp",  LocalDateTime.now().toString());
        wsMsg.put("version",    WebSocketMessageType.PROTOCOL_VERSION);

        switch (eventType) {
            case WebSocketMessageType.TOOL_CALL_START:
                wsMsg.put("type",      WebSocketMessageType.TOOL_CALL_START);
                wsMsg.put("tool_data", eventData);
                return wsMsg;

            case "tool_call":
                wsMsg.put("type",      "tool_call");
                wsMsg.put("tool_data", eventData);
                return wsMsg;

            case WebSocketMessageType.TOOL_CALLS_DONE:
                toolCallCount[0]++;
                wsMsg.put("type",       WebSocketMessageType.TOOL_CALLS_DONE);
                wsMsg.put("tool_calls", eventData);
                log.info("工具调用完成 #{}, requestId: {}", toolCallCount[0], requestId);
                return wsMsg;

            case "thinking_chunk":
                wsMsg.put("type",  WebSocketMessageType.THINKING_CHUNK);
                wsMsg.put("chunk", String.valueOf(eventData));
                return wsMsg;

            case WebSocketMessageType.PLAN:
                wsMsg.put("type", WebSocketMessageType.PLAN);
                wsMsg.put("plan", eventData);
                return wsMsg;

            case WebSocketMessageType.APPROVAL_REQUIRED:
                wsMsg.put("type", WebSocketMessageType.APPROVAL_REQUIRED);
                wsMsg.put("approval", eventData);
                return wsMsg;

            case "token":
                String token = String.valueOf(eventData);
                fullMsg.append(token);
                wsMsg.put("type",  WebSocketMessageType.CHAT_TOKEN);
                wsMsg.put("token", token);
                return wsMsg;

            case "done":
                double responseTime = (System.currentTimeMillis() - startTime) / 1000.0;
                log.info("流式聊天完成, requestId: {}, 总耗时: {}秒, "
                                + "工具调用次数: {}, 回复长度: {}字",
                        requestId, responseTime,
                        toolCallCount[0], fullMsg.length());
                wsMsg.put("type",          WebSocketMessageType.CHAT_DONE);
                wsMsg.put("message",       fullMsg.toString());
                wsMsg.put("response_time", responseTime);
                if (eventData instanceof Map) {
                    Map<?, ?> doneData = (Map<?, ?>) eventData;
                    if (doneData.get("user_message_id") != null) {
                        wsMsg.put("user_message_id", doneData.get("user_message_id"));
                    }
                    if (doneData.get("assistant_message_id") != null) {
                        wsMsg.put("assistant_message_id", doneData.get("assistant_message_id"));
                    }
                }
                return wsMsg;

            case "task_update":
                wsMsg.put("type",      WebSocketMessageType.TASK_UPDATE);
                wsMsg.put("task_data", eventData);
                log.info("任务状态更新, requestId: {}", requestId);
                return wsMsg;

            case "task_blocked":
                wsMsg.put("type",      WebSocketMessageType.TASK_BLOCKED);
                wsMsg.put("task_data", eventData);
                log.info("任务被阻塞, requestId: {}", requestId);
                return wsMsg;

            case "error":
                log.error("流式聊天错误, requestId: {}, 错误: {}", requestId, eventData);
                wsMsg.put("type",    WebSocketMessageType.ERROR);
                wsMsg.put("message", String.valueOf(eventData));
                return wsMsg;

            default:
                return null;
        }
    }

    /** 需要静默发送（不逐条 INFO 日志）的事件类型。 */
    private boolean isQuietWsEvent(String eventType) {
        return switch (eventType) {
            case "thinking_chunk", "token",
                    WebSocketMessageType.TOOL_CALL_START, "tool_call",
                    "task_update", "task_blocked", WebSocketMessageType.PLAN,
                    WebSocketMessageType.APPROVAL_REQUIRED -> true;
            default -> false;
        };
    }

    private void sendFallbackDone(WebSocketSession session, String requestId, long startTime) {
        try {
            double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
            Map<String, Object> doneMsg = new HashMap<>();
            doneMsg.put("type",          WebSocketMessageType.CHAT_DONE);
            doneMsg.put("message",       "");
            doneMsg.put("response_time", elapsed);
            doneMsg.put("request_id",    requestId);
            doneMsg.put("timestamp",     LocalDateTime.now().toString());
            JsonUtil.sendJsonMessageQuiet(session, doneMsg);
            log.info("finally 补发 chat_done, requestId: {}", requestId);
        } catch (Exception ignored) {}
    }

    /**
     * 把一轮对话（用户消息 + 助手回复）持久化到会话历史，返回分配的 message_id。
     * 2026-08-15 补齐：Java REST/WS 聊天此前不落库，撤回级联与历史恢复依赖它。
     */
    private Map<String, String> persistTurn(ChatRequest request, String answer) {
        if (conversationService == null || request.getMessage() == null
                || request.getMessage().isBlank()) {
            return null;
        }
        String userMsgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String assistantMsgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<Map<String, Object>> messages = new ArrayList<>(2);
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("id", userMsgId);
        userMsg.put("role", "user");
        userMsg.put("content", request.getMessage());
        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("id", assistantMsgId);
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", answer == null ? "" : answer);
        messages.add(userMsg);
        messages.add(assistantMsg);
        try {
            conversationService.append(request.getUserId(), request.getSessionId(), messages);
            return Map.of("user", userMsgId, "assistant", assistantMsgId);
        } catch (Exception e) {
            log.warn("会话持久化失败: {}", e.getMessage());
            return null;
        }
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    // ── 系统信息（java 模式：本地组件组装） ─────────────────────

    public Map<String, Object> getRealSystemInfo() {
        Map<String, Object> info = new HashMap<>();
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory() / 1024 / 1024;
        long used  = total - runtime.freeMemory() / 1024 / 1024;
        info.put("java_version",    System.getProperty("java.version"));
        info.put("memory_total_mb", total);
        info.put("memory_used_mb",  used);
        info.put("memory_percent",  String.format("%.1f", (double) used / total * 100));
        info.put("runtime_mode",    "java");

        boolean ollamaOk = modelService != null && modelService.ollamaAvailable();
        info.put("ollama_available", ollamaOk);
        info.put("agent_model", modelService == null ? "未知"
                : modelService.resolveModel(null));
        info.put("agent_ready", true);

        Map<String, String> cloud = modelService == null
                ? Map.of() : modelService.activeCloudConfig();
        boolean cloudConfigured = cloud.get("api_key") != null
                && !cloud.get("api_key").isBlank()
                && cloud.get("model") != null && !cloud.get("model").isBlank();
        info.put("cloud_mode",     cloudConfigured);
        info.put("cloud_model",    cloudConfigured ? cloud.get("model") : "");
        info.put("cloud_base_url", cloudConfigured ? cloud.get("base_url") : "");
        info.put("tools_count",    toolExecutor == null ? 0 : toolExecutor.definitions().size());
        info.put("python_status",  "java-only");
        return info;
    }
}

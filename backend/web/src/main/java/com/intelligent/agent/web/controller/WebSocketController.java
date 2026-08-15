package com.intelligent.agent.web.controller;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.util.JsonUtil;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.dto.WebSocketMessageType;
import com.intelligent.agent.web.service.AgentService;
import com.intelligent.agent.web.infrastructure.scheduler.TaskSchedulerService;

import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 描述：
 *
 * @author lin miao
 * @date 2026/5/1
 */
@Slf4j
@Component
public class WebSocketController extends TextWebSocketHandler {


    // 存储所有WebSocket会话
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentService agentService;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("streamExecutor")
    private ExecutorService streamExecutor;

    @Autowired(required = false)
    private TaskSchedulerService taskSchedulerService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);

        log.info("✅ WebSocket连接建立, sessionId: {}, 当前连接数: {}", sessionId, sessions.size());

        try {
            // 发送连接确认消息
            Map<String, Object> response = new HashMap<>();
            response.put("type", WebSocketMessageType.CONNECTION_ESTABLISHED);
            response.put("message", "WebSocket连接已建立");
            response.put("timestamp", LocalDateTime.now().toString());
            response.put("request_id", "conn-" + sessionId.substring(0, 8));

            JsonUtil.sendJsonMessage(session, response);
            log.info("已发送连接确认消息");

            // 异步发送系统信息：内部会探测 Ollama 健康，避免阻塞 Spring WS accept 线程。
            streamExecutor.submit(() -> {
                try { sendSystemInfo(session); } catch (Exception e) {
                    log.warn("异步发送系统信息失败: {}", e.getMessage());
                }
            });

        } catch (Exception e) {
            log.error("发送连接确认消息失败", e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        // DEBUG 级别 + 截断：避免完整用户消息（含敏感内容）写入生产日志
        log.debug("收到原始消息(前200字符): {}", payload.length() > 200 ? payload.substring(0, 200) + "..." : payload);
        log.debug("消息长度: {}", payload.length());

        try {
            // 解析消息
            Map<String, Object> request = objectMapper.readValue(payload, Map.class);
            String type = (String) request.get("type");

            if (WebSocketMessageType.CHAT_MESSAGE.equals(type)) {
                handleChatMessage(session, request);
            } else if (WebSocketMessageType.PING.equals(type)) {
                // 心跳响应
                Map<String, Object> pongResponse = new HashMap<>();
                pongResponse.put("type", WebSocketMessageType.PONG);
                pongResponse.put("timestamp", LocalDateTime.now().toString());
                pongResponse.put("request_id", request.get("request_id"));

                JsonUtil.sendJsonMessage(session, pongResponse);
            } else if (WebSocketMessageType.GET_SYSTEM_INFO.equals(type)) {
                sendSystemInfo(session);
            } else {
                log.warn("未知消息类型: {}", type);

                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("type", WebSocketMessageType.ERROR);
                errorResponse.put("message", "未知消息类型: " + type);
                errorResponse.put("timestamp", LocalDateTime.now().toString());
                errorResponse.put("request_id", request.get("request_id"));

                JsonUtil.sendJsonMessage(session, errorResponse);
            }

        } catch (Exception e) {
            log.error("处理消息失败", e);

            // 发送错误响应
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("type", WebSocketMessageType.ERROR);
            errorResponse.put("message", "处理消息失败: " + e.getMessage());
            errorResponse.put("timestamp", LocalDateTime.now().toString());
            errorResponse.put("request_id", "error-" + System.currentTimeMillis());

            JsonUtil.sendJsonMessage(session, errorResponse);
        }
    }

    private void handleChatMessage(WebSocketSession session,
                                   Map<String, Object> request) throws IOException {
        String  userMessage  = (String)  request.get("message");
        Boolean useTools     = (Boolean) request.get("use_tools");
        Boolean useMemory    = (Boolean) request.get("use_memory");
        String  requestId    = (String)  request.get("request_id");
        String  projectId    = (String)  request.get("project_id");
        String  sessionId    = (String)  request.get("session_id");
        String  model        = (String)  request.get("model");
        String  imageBase64  = (String)  request.get("image_base64");
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> pendingTasks =
            (java.util.List<java.util.Map<String, Object>>) request.get("pending_tasks");
        if (requestId == null) {
            requestId = "req-" + System.currentTimeMillis();
        }

        // 从握手时存入的 session 属性中提取真实用户 ID
        String userId = (String) session.getAttributes().get("userId");
        log.info("处理流式聊天消息: {}, requestId: {}, userId: {}, projectId: {}, sessionId: {}",
                 userMessage, requestId, userId, projectId, sessionId);

        // 发送 thinking 状态
        Map<String, Object> thinking = new HashMap<>();
        thinking.put("type",       WebSocketMessageType.THINKING);
        thinking.put("message",    "正在思考...");
        thinking.put("timestamp",  LocalDateTime.now().toString());
        thinking.put("request_id", requestId);
        JsonUtil.sendJsonMessage(session, thinking);

        // 构建请求
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setMessage(userMessage);
        chatRequest.setUseTools(useTools  != null ? useTools  : true);
        chatRequest.setUseMemory(useMemory != null ? useMemory : true);
        chatRequest.setProjectId(projectId);
        chatRequest.setSessionId(sessionId);
        chatRequest.setModel(model);
        chatRequest.setPendingTasks(pendingTasks);
        chatRequest.setImageBase64(imageBase64);
        chatRequest.setUserId(userId);  // 透传真实用户 ID
        chatRequest.setRequestId(requestId);  // G4 traceID 关联

        // 异步流式处理；线程池满时向客户端返回 503 而不是卡住 Tomcat 线程
        long startTime = System.currentTimeMillis();
        try {
            agentService.streamChatAsync(chatRequest, session, requestId, startTime);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("流式线程池已满，拒绝请求: {}", requestId);
            Map<String, Object> busyMsg = new HashMap<>();
            busyMsg.put("type",       WebSocketMessageType.ERROR);
            busyMsg.put("message",    "服务繁忙，请稍后再试");
            busyMsg.put("request_id", requestId);
            busyMsg.put("timestamp",  LocalDateTime.now().toString());
            JsonUtil.sendJsonMessageQuiet(session, busyMsg);
        }
    }

    private void sendSystemInfo(WebSocketSession session) throws IOException {
        try {
            Map<String, Object> systemInfo = agentService.getRealSystemInfo();

            Map<String, Object> response = new HashMap<>();
            response.put("type", WebSocketMessageType.SYSTEM_INFO);
            response.put("info", systemInfo);
            response.put("timestamp", LocalDateTime.now().toString());
            response.put("request_id", "sysinfo-" + System.currentTimeMillis());

            JsonUtil.sendJsonMessage(session, response);
            log.info("已发送系统信息");

        } catch (Exception e) {
            log.error("发送系统信息失败", e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("type", WebSocketMessageType.ERROR);
            errorResponse.put("message", "获取系统信息失败: " + e.getMessage());
            errorResponse.put("timestamp", LocalDateTime.now().toString());
            errorResponse.put("request_id", "sysinfo-error");

            JsonUtil.sendJsonMessage(session, errorResponse);
        }
    }

    /**
     * 每 5 秒轮询本地调度通知队列。
     * <p>
     * 2026-08-15：通知按 user_id 分发——系统级（无 user_id）广播给所有在线会话；
     * 用户级只推给该用户自己的会话；目标用户不在线时重新入队，等待其上线后再推。
     * 此前全局广播会造成多用户通知串台。
     */
    @Scheduled(fixedDelay = 5000)
    public void pushPendingNotifications() {
        if (sessions.isEmpty()) return;
        List<Map<String, Object>> notifications = taskSchedulerService == null
                ? List.of() : taskSchedulerService.pollNotifications();
        if (notifications.isEmpty()) return;

        // 按会话 userId 分组（握手时 JwtHandshakeInterceptor 写入 session 属性）
        Map<String, List<WebSocketSession>> sessionsByUser = new HashMap<>();
        for (WebSocketSession session : sessions.values()) {
            if (!session.isOpen()) continue;
            Object uid = session.getAttributes().get("userId");
            sessionsByUser.computeIfAbsent(
                    uid == null ? "" : String.valueOf(uid), k -> new ArrayList<>()).add(session);
        }
        if (sessionsByUser.isEmpty()) {
            taskSchedulerService.requeue(notifications);
            return;
        }

        List<Map<String, Object>> systemNotifications = new ArrayList<>();
        Map<String, List<Map<String, Object>>> userNotifications = new HashMap<>();
        for (Map<String, Object> n : notifications) {
            Object uid = n.get("user_id");
            if (uid == null || String.valueOf(uid).isBlank()) {
                systemNotifications.add(n);
            } else {
                userNotifications.computeIfAbsent(String.valueOf(uid),
                        k -> new ArrayList<>()).add(n);
            }
        }

        // 系统级通知：广播到所有在线会话
        if (!systemNotifications.isEmpty()) {
            Map<String, Object> push = new HashMap<>();
            push.put("type",          WebSocketMessageType.NOTIFICATION);
            push.put("notifications", systemNotifications);
            push.put("count",         systemNotifications.size());
            push.put("timestamp",     LocalDateTime.now().toString());
            sessions.values().stream()
                    .filter(WebSocketSession::isOpen)
                    .forEach(s -> JsonUtil.sendJsonMessageQuiet(s, push));
        }

        // 用户级通知：只推给该用户自己的会话；不在线则重新入队
        List<Map<String, Object>> undelivered = new ArrayList<>();
        int deliveredUsers = 0;
        for (Map.Entry<String, List<Map<String, Object>>> entry : userNotifications.entrySet()) {
            List<WebSocketSession> userSessions = sessionsByUser.get(entry.getKey());
            if (userSessions == null || userSessions.isEmpty()) {
                undelivered.addAll(entry.getValue());
                continue;
            }
            deliveredUsers++;
            Map<String, Object> push = new HashMap<>();
            push.put("type",          WebSocketMessageType.NOTIFICATION);
            push.put("notifications", entry.getValue());
            push.put("count",         entry.getValue().size());
            push.put("timestamp",     LocalDateTime.now().toString());
            userSessions.forEach(s -> JsonUtil.sendJsonMessageQuiet(s, push));
        }
        if (!undelivered.isEmpty()) {
            taskSchedulerService.requeue(undelivered);
        }
        log.info("通知分发: 系统 {} 条, 用户 {} 人, 未送达重入队 {} 条",
                systemNotifications.size(), deliveredUsers, undelivered.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        log.info("❌ WebSocket连接关闭, sessionId: {}, 状态: {}, 当前连接数: {}",
                sessionId, status, sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket传输错误, sessionId: {}", session.getId(), exception);
    }

}

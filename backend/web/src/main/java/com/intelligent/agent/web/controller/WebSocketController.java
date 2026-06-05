package com.intelligent.agent.web.controller;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.util.JsonUtil;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.dto.WebSocketMessageType;
import com.intelligent.agent.web.service.AgentService;

import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
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

    @Value("${intelligent-agent.python-service.base-url:http://localhost:8000}")
    private String pythonServiceBaseUrl;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("streamExecutor")
    private ExecutorService streamExecutor;

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

            // 异步发送系统信息：getRealSystemInfo() 内部做 2 次 HTTP 调用到 Python，
            // 同步执行会阻塞 Spring WS accept 线程长达 60s（超时两倍）。
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
        String  userMessage = (String)  request.get("message");
        Boolean useTools    = (Boolean) request.get("use_tools");
        Boolean useMemory   = (Boolean) request.get("use_memory");
        String  requestId   = (String)  request.get("request_id");
        String  projectId    = (String)  request.get("project_id");
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> pendingTasks =
            (java.util.List<java.util.Map<String, Object>>) request.get("pending_tasks");
        if (requestId == null) {
            requestId = "req-" + System.currentTimeMillis();
        }

        // 从握手时存入的 session 属性中提取真实用户 ID
        String userId = (String) session.getAttributes().get("userId");
        log.info("处理流式聊天消息: {}, requestId: {}, userId: {}, projectId: {}",
                 userMessage, requestId, userId, projectId);

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
        chatRequest.setPendingTasks(pendingTasks);
        chatRequest.setUserId(userId);  // 透传真实用户 ID

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
     * 每 5 秒轮询一次 Python 通知队列。
     * 有通知时广播给所有在线 WS 会话，取代前端 30s 轮询方案。
     */
    @Scheduled(fixedDelay = 5000)
    public void pushPendingNotifications() {
        if (sessions.isEmpty()) return;
        List<Map<String, Object>> notifications = agentService.pollNotifications();
        if (notifications.isEmpty()) return;

        Map<String, Object> push = new HashMap<>();
        push.put("type",          WebSocketMessageType.NOTIFICATION);
        push.put("notifications", notifications);
        push.put("count",         notifications.size());
        push.put("timestamp",     LocalDateTime.now().toString());

        sessions.values().stream()
                .filter(WebSocketSession::isOpen)
                .forEach(s -> JsonUtil.sendJsonMessageQuiet(s, push));
        log.info("推送 {} 条通知给 {} 个在线会话", notifications.size(), sessions.size());
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
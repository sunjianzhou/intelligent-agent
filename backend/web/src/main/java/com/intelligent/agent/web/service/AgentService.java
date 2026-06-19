package com.intelligent.agent.web.service;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.dto.WebSocketMessageType;
import com.intelligent.agent.web.util.JsonUtil;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;

/**
 * 描述：
 *
 * @author lin miao
 * @date 2026/5/1
 */
@Slf4j
@Service
public class AgentService {

    @Value("${intelligent-agent.python-service.base-url:http://localhost:8000}")
    private String pythonServiceBaseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService streamExecutor;
    private final CloseableHttpClient streamHttpClient;

    // token 管理委托给 PythonProxyService，不再重复维护
    @Autowired
    private PythonProxyService proxy;

    public AgentService(ObjectMapper objectMapper,
                        @Qualifier("streamExecutor") ExecutorService streamExecutor) {
        this.objectMapper   = objectMapper;
        this.streamExecutor = streamExecutor;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(600000);

        this.restTemplate = new RestTemplateBuilder()
                .requestFactory(() -> factory)
                .build();

        restTemplate.getMessageConverters().stream()
                .filter(c -> c instanceof StringHttpMessageConverter)
                .forEach(c -> ((StringHttpMessageConverter) c)
                        .setDefaultCharset(StandardCharsets.UTF_8));

        restTemplate.getMessageConverters().stream()
                .filter(c -> c instanceof MappingJackson2HttpMessageConverter)
                .forEach(c -> ((MappingJackson2HttpMessageConverter) c)
                        .setDefaultCharset(StandardCharsets.UTF_8));

        // 连接池：max-per-route 20 支持多用户并发 SSE 流
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(100);
        cm.setDefaultMaxPerRoute(20);
        // socket timeout 620s：Python 推理最长 300s，留 320s 余量；
        // 防止 Python 挂住时 worker 线程永久阻塞
        RequestConfig reqConfig = RequestConfig.custom()
                .setSocketTimeout(620_000)
                .setConnectTimeout(30_000)
                .build();
        this.streamHttpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(reqConfig)
                .build();
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAcceptCharset(Collections.singletonList(StandardCharsets.UTF_8));
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("Authorization", "Bearer " + proxy.getServiceToken());
        return headers;
    }

    @PreDestroy
    public void destroy() {
        try {
            streamHttpClient.close();
        } catch (IOException e) {
            log.warn("关闭 HTTP 客户端失败", e);
        }
    }

    // ── 非流式（ChatController / 飞书接入用）──────────────────
    public String chat(ChatRequest request) {
        Map<String, Object> result = chatFull(request);
        return result.getOrDefault("response", "服务异常").toString();
    }

    public Map<String, Object> chatFull(ChatRequest request) {
        try {
            String url = pythonServiceBaseUrl + "/api/chat";
            log.info("调用Python智能体服务: {}", url);

            Map<String, Object> body = new HashMap<>();
            body.put("message",    request.getMessage());
            body.put("use_tools",  request.getUseTools());
            body.put("use_memory", request.getUseMemory());
            if (request.getProjectId() != null) {
                body.put("project_id", request.getProjectId());
            }
            if (request.getSessionId() != null) {
                body.put("session_id", request.getSessionId());
            }
            if (request.getPendingTasks() != null && !request.getPendingTasks().isEmpty()) {
                body.put("pending_tasks", request.getPendingTasks());
            }
            if (request.getImageBase64() != null && !request.getImageBase64().isEmpty()) {
                body.put("image_base64", request.getImageBase64());
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, authHeaders());
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> result = objectMapper.readValue(response.getBody(), Map.class);
                log.info("Python服务响应成功");
                return result;
            }
            Map<String, Object> err = new HashMap<>();
            err.put("response",   "Python服务返回错误: " + response.getStatusCode());
            err.put("tool_calls", Collections.emptyList());
            return err;
        } catch (Exception e) {
            log.error("调用智能体服务失败", e);
            Map<String, Object> err = new HashMap<>();
            err.put("response",   "智能体服务暂时不可用: " + e.getMessage());
            err.put("tool_calls", Collections.emptyList());
            return err;
        }
    }

    // ── 流式（WebSocket 用）────────────────────────────────────
    public void streamChatAsync(ChatRequest request, WebSocketSession session,
                                String requestId, long startTime) {
        streamExecutor.submit(() ->
                doStreamChat(request, session, requestId, startTime));
    }

    private void doStreamChat(ChatRequest request, WebSocketSession session,
                              String requestId, long startTime) {
        String url = pythonServiceBaseUrl + "/api/chat/stream";
        log.info("开始流式聊天: {}, url: {}", requestId, url);

        // 追踪 chat_done 是否已发出：无论流正常结束、异常中断还是 session 关闭，
        // finally 块都会补发，确保前端 isStreaming 状态总能被清除。
        final boolean[] chatDoneEmitted = {false};

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("message",    request.getMessage());
            body.put("use_tools",  request.getUseTools());
            body.put("use_memory", request.getUseMemory());
            if (request.getProjectId() != null) {
                body.put("project_id", request.getProjectId());
            }
            if (request.getSessionId() != null) {
                body.put("session_id", request.getSessionId());
            }
            if (request.getPendingTasks() != null && !request.getPendingTasks().isEmpty()) {
                body.put("pending_tasks", request.getPendingTasks());
            }
            if (request.getImageBase64() != null && !request.getImageBase64().isEmpty()) {
                body.put("image_base64", request.getImageBase64());
            }
            String bodyJson = objectMapper.writeValueAsString(body);

            HttpPost post = new HttpPost(url);
            post.setHeader("Content-Type", "application/json; charset=UTF-8");
            post.setHeader("Accept",       "text/event-stream");
            post.setEntity(new StringEntity(bodyJson, StandardCharsets.UTF_8));
            // token + 可选 X-User-Id 由 PythonProxyService 统一管理
            proxy.authHeaders(request.getUserId()).forEach(
                    (name, values) -> values.forEach(v -> post.setHeader(name, v))
            );

            try (CloseableHttpResponse resp = this.streamHttpClient.execute(post)) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                resp.getEntity().getContent(), StandardCharsets.UTF_8));

                StringBuilder fullMsg      = new StringBuilder();
                int           toolCallCount = 0;
                String        line;

                while ((line = reader.readLine()) != null) {
                    if (!session.isOpen()) {
                        break;
                    }
                    if (!line.startsWith("data: ")) {
                        continue;
                    }

                    String             jsonStr   = line.substring(6).trim();
                    Map<String, Object> event    = objectMapper.readValue(jsonStr, Map.class);
                    String             eventType = (String) event.get("type");
                    Object             eventData = event.get("data");

                    Map<String, Object> wsMsg = new HashMap<>();
                    wsMsg.put("request_id", requestId);
                    wsMsg.put("timestamp",  LocalDateTime.now().toString());

                    wsMsg.put("version", WebSocketMessageType.PROTOCOL_VERSION);
                    switch (eventType) {
                        case WebSocketMessageType.TOOL_CALL_START:
                            wsMsg.put("type",      WebSocketMessageType.TOOL_CALL_START);
                            wsMsg.put("tool_data", eventData);
                            JsonUtil.sendJsonMessageQuiet(session, wsMsg);
                            break;

                        case "tool_call":
                            // Python 单条工具结果事件（协议对称，透传给前端备用）
                            wsMsg.put("type",      "tool_call");
                            wsMsg.put("tool_data", eventData);
                            JsonUtil.sendJsonMessageQuiet(session, wsMsg);
                            break;

                        case WebSocketMessageType.TOOL_CALLS_DONE:
                            toolCallCount++;
                            wsMsg.put("type",       WebSocketMessageType.TOOL_CALLS_DONE);
                            wsMsg.put("tool_calls", eventData);
                            JsonUtil.sendJsonMessage(session, wsMsg);
                            log.info("工具调用完成 #{}, requestId: {}", toolCallCount, requestId);
                            break;

                        case "thinking_chunk":
                            wsMsg.put("type",  WebSocketMessageType.THINKING_CHUNK);
                            wsMsg.put("chunk", String.valueOf(eventData));
                            JsonUtil.sendJsonMessageQuiet(session, wsMsg);
                            break;

                        case "token":
                            String token = String.valueOf(eventData);
                            fullMsg.append(token);
                            wsMsg.put("type",  WebSocketMessageType.CHAT_TOKEN);
                            wsMsg.put("token", token);
                            JsonUtil.sendJsonMessageQuiet(session, wsMsg);
                            break;

                        case "done":
                            double responseTime =
                                    (System.currentTimeMillis() - startTime) / 1000.0;
                            log.info("流式聊天完成, requestId: {}, 总耗时: {}秒, "
                                            + "工具调用次数: {}, 回复长度: {}字",
                                    requestId, responseTime,
                                    toolCallCount, fullMsg.length());
                            wsMsg.put("type",          WebSocketMessageType.CHAT_DONE);
                            wsMsg.put("message",       fullMsg.toString());
                            wsMsg.put("response_time", responseTime);
                            JsonUtil.sendJsonMessage(session, wsMsg);
                            chatDoneEmitted[0] = true;
                            break;

                        case "task_update":
                            wsMsg.put("type",      WebSocketMessageType.TASK_UPDATE);
                            wsMsg.put("task_data", eventData);
                            JsonUtil.sendJsonMessageQuiet(session, wsMsg);
                            log.info("任务状态更新, requestId: {}", requestId);
                            break;

                        case "task_blocked":
                            wsMsg.put("type",      WebSocketMessageType.TASK_BLOCKED);
                            wsMsg.put("task_data", eventData);
                            JsonUtil.sendJsonMessageQuiet(session, wsMsg);
                            log.info("任务被阻塞, requestId: {}", requestId);
                            break;

                        case "error":
                            log.error("流式聊天错误, requestId: {}, 错误: {}",
                                    requestId, eventData);
                            wsMsg.put("type",    WebSocketMessageType.ERROR);
                            wsMsg.put("message", String.valueOf(eventData));
                            JsonUtil.sendJsonMessage(session, wsMsg);
                            break;

                        default:
                            break;
                    }
                }
            }
        } catch (Exception e) {
            log.error("流式聊天异常, requestId: {}", requestId, e);
            try {
                Map<String, Object> errMsg = new HashMap<>();
                errMsg.put("type",       WebSocketMessageType.ERROR);
                errMsg.put("message",    "流式聊天失败: " + e.getMessage());
                errMsg.put("request_id", requestId);
                JsonUtil.sendJsonMessageQuiet(session, errMsg);
            } catch (Exception ignored) {}
        } finally {
            // 兜底保证：无论流正常结束、SSE 未收到 done 事件、异常中断还是 session 关闭，
            // 前端都能收到 chat_done 以清除 isStreaming 状态，避免输入框被永久锁死。
            if (!chatDoneEmitted[0]) {
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
        }
    }

    public ObjectMapper getObjectMapper() { return objectMapper; }

    // ── 系统信息 ───────────────────────────────────────────────
    public Map<String, Object> getRealSystemInfo() {
        Map<String, Object> info = new HashMap<>();
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory() / 1024 / 1024;
        long used  = total - runtime.freeMemory() / 1024 / 1024;
        info.put("java_version",    System.getProperty("java.version"));
        info.put("memory_total_mb", total);
        info.put("memory_used_mb",  used);
        info.put("memory_percent",  String.format("%.1f", (double) used / total * 100));

        try {
            String url = pythonServiceBaseUrl + "/health";
            HttpEntity<Void> req = new HttpEntity<>(authHeaders());
            ResponseEntity<String> res = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            if (res.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> pythonInfo =
                        objectMapper.readValue(res.getBody(), Map.class);
                info.put("ollama_available", pythonInfo.get("ollama_available"));
                info.put("agent_model",      pythonInfo.get("model"));
                info.put("agent_ready",      pythonInfo.get("agent_ready"));
                info.put("cloud_mode",       pythonInfo.get("cloud_mode"));
                info.put("cloud_model",      pythonInfo.get("cloud_model"));
                info.put("cloud_base_url",   pythonInfo.get("cloud_base_url"));
                info.put("python_status",    "connected");
            }
        } catch (Exception e) {
            info.put("ollama_available", false);
            info.put("agent_model",      "未知");
            info.put("agent_ready",      false);
            info.put("cloud_mode",       false);
            info.put("python_status",    "disconnected");
        }

        try {
            String url = pythonServiceBaseUrl + "/api/tools/list";
            HttpEntity<Void> req = new HttpEntity<>(authHeaders());
            ResponseEntity<String> res = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            if (res.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> toolsData =
                        objectMapper.readValue(res.getBody(), Map.class);
                List<?> tools = (List<?>) toolsData.get("tools");
                info.put("tools_count", tools != null ? tools.size() : 0);
            }
        } catch (Exception e) {
            info.put("tools_count", 0);
        }

        info.put("timestamp", LocalDateTime.now().toString());
        return info;
    }

    public Map<String, Object> getModels() {
        return getModels(null);
    }

    public Map<String, Object> getModels(String userId) {
        try {
            String url = pythonServiceBaseUrl + "/api/models";
            org.springframework.http.HttpHeaders headers = authHeaders();
            if (userId != null && !userId.isEmpty()) {
                headers.set("X-User-Id", userId);
            }
            HttpEntity<Void> req = new HttpEntity<>(headers);
            ResponseEntity<String> res = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            if (res.getStatusCode().is2xxSuccessful()) {
                return objectMapper.readValue(res.getBody(), Map.class);
            }
        } catch (Exception e) {
            log.error("获取模型列表失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("available_models", new ArrayList<>());
        fallback.put("current_model",    "未知");
        fallback.put("ollama_available", false);
        return fallback;
    }

    public Map<String, Object> switchModel(String modelName) {
        return switchModel(modelName, null);
    }

    /** 取出 Python 通知队列中所有待推送通知（执行后 Python 端清空队列）。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> pollNotifications() {
        try {
            String url = pythonServiceBaseUrl + "/api/notifications/poll";
            HttpEntity<Void> req = new HttpEntity<>(authHeaders());
            ResponseEntity<String> res = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            if (res.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> data = objectMapper.readValue(res.getBody(), Map.class);
                Object notifs = data.get("notifications");
                if (notifs instanceof List) {
                    return (List<Map<String, Object>>) notifs;
                }
            }
        } catch (Exception e) {
            log.debug("通知轮询失败: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    public Map<String, Object> switchModel(String modelName, String userId) {
        try {
            String url = pythonServiceBaseUrl + "/api/model/switch";
            Map<String, Object> body = new HashMap<>();
            body.put("model", modelName);
            org.springframework.http.HttpHeaders headers = authHeaders();
            if (userId != null && !userId.isEmpty()) {
                headers.set("X-User-Id", userId);
            }
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> res = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);
            if (res.getStatusCode().is2xxSuccessful()) {
                return objectMapper.readValue(res.getBody(), Map.class);
            }
        } catch (Exception e) {
            log.error("切换模型失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("success", false);
        fallback.put("message", "切换模型失败，请检查 Python 服务");
        return fallback;
    }


}

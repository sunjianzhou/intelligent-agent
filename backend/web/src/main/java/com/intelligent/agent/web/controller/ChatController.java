package com.intelligent.agent.web.controller;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.dto.response.ApiResponse;
import com.intelligent.agent.web.api.chat.LocalChatService;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.service.AgentService;
import com.intelligent.agent.web.service.PythonProxyService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import jakarta.validation.Valid;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
        this(agentService, null, null, "python", null);
    }

    @Autowired
    public ChatController(AgentService agentService,
                          LocalChatService localChatService,
                          PythonProxyService proxy,
                          @Value("${ai.runtime.mode:python}") String runtimeMode,
                          ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.localChatService = localChatService;
        this.proxy = proxy;
        this.runtimeMode = runtimeMode;
        this.objectMapper = objectMapper;
    }

    private final LocalChatService localChatService;
    private final PythonProxyService proxy;
    private final String runtimeMode;
    private final ObjectMapper objectMapper;

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

    /** SSE 流式聊天（CLI 契约 /api/chat/stream）。 */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequest request) {
        if ("java".equals(runtimeMode) || "shadow".equals(runtimeMode)) {
            return localChatService.stream(request)
                    .map(event -> ServerSentEvent.<String>builder()
                            .event(event.type())
                            .data(json(event))
                            .build())
                    .onErrorResume(e -> Flux.just(ServerSentEvent.builder("event")
                            .data("{\"type\":\"error\",\"data\":\"" + escape(e.getMessage()) + "\"}")
                            .build()));
        }
        return pythonStreamProxy(request);
    }

    private Flux<ServerSentEvent<String>> pythonStreamProxy(ChatRequest request) {
        return Flux.<ServerSentEvent<String>>create(sink -> {
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("message", request.getMessage());
                body.put("use_tools", request.getUseTools());
                body.put("use_memory", request.getUseMemory());
                if (request.getProjectId() != null) body.put("project_id", request.getProjectId());
                if (request.getSessionId() != null) body.put("session_id", request.getSessionId());
                String payload = objectMapper.writeValueAsString(body);
                String url = proxy.getBaseUrl() + "/api/chat/stream";

                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMinutes(10))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(payload));
                proxy.authHeaders(request.getUserId()).forEach(
                        (name, values) -> values.forEach(v -> builder.header(name, v)));

                HttpResponse<java.io.InputStream> response = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build()
                        .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            sink.next(ServerSentEvent.builder("event")
                                    .data(line.substring(6).trim()).build());
                        }
                    }
                }
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        }, reactor.core.publisher.FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
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

package com.intelligent.agent.web.ai.llm.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.intelligent.agent.web.ai.llm.AbstractHttpLlmProvider;
import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProviderException;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.llm.OllamaOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 Ollama provider：POST /api/chat，NDJSON 行协议逐 token 产出事件。
 * 请求体与 Python {@code services/ollama_provider.py} 保持一致。
 */
public class OllamaLlmProvider extends AbstractHttpLlmProvider {

    private final String baseUrl;
    private final String defaultModel;
    private final OllamaOptions defaultOptions;

    public OllamaLlmProvider(String baseUrl, String defaultModel,
                             OllamaOptions defaultOptions, Duration timeout) {
        super(timeout);
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.defaultModel = defaultModel;
        this.defaultOptions = defaultOptions == null ? OllamaOptions.defaults() : defaultOptions;
    }

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public Flux<ModelEvent> stream(ChatTurn turn) {
        return streamLines(chatRequest(turn, true), (line, sink) -> {
            try {
                JsonNode node = MAPPER.readTree(line);
                if (node.has("error")) {
                    sink.next(ModelEvent.error(node.path("error").asText("ollama error")));
                    return true;
                }
                String token = node.path("message").path("content").asText("");
                if (!token.isEmpty()) {
                    sink.next(ModelEvent.token(token));
                }
                if (node.path("done").asBoolean(false)) {
                    sink.next(ModelEvent.done(Map.of()));
                    return true;
                }
                return false;
            } catch (Exception e) {
                sink.next(ModelEvent.error("invalid ollama stream line: " + redact(e.getMessage())));
                return true;
            }
        });
    }

    @Override
    public Mono<String> complete(ChatTurn turn) {
        return completeBody(chatRequest(turn, false)).map(body -> {
            try {
                return MAPPER.readTree(body).path("message").path("content").asText("").trim();
            } catch (Exception e) {
                throw new LlmProviderException(redact(e.getMessage()), e);
            }
        });
    }

    private HttpRequest chatRequest(ChatTurn turn, boolean stream) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", resolveModel(turn));
            payload.put("messages", toMessages(turn));
            payload.put("stream", stream);
            payload.put("keep_alive", keepAliveValue());
            payload.put("options", resolveOptions(turn));
            String body = MAPPER.writeValueAsString(payload);
            return jsonRequest(baseUrl + "/api/chat")
                    .header("Accept", "application/x-ndjson")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (Exception e) {
            throw new LlmProviderException(redact(e.getMessage()), e);
        }
    }

    private String resolveModel(ChatTurn turn) {
        String requested = turn.model();
        return requested == null || requested.isBlank() ? defaultModel : requested.trim();
    }

    /**
     * keep_alive 参数：纯数字字符串按数字发送（"-1"=永久驻留），
     * 其余（如 "5m" / "1h"）按时长字符串发送。Ollama 0.5.x 拒绝字符串 "-1"（HTTP 400）。
     */
    private Object keepAliveValue() {
        String raw = defaultOptions.keepAlive();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return trimmed;
        }
    }

    private List<Map<String, Object>> toMessages(ChatTurn turn) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage m : turn.messages()) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", m.role());
            msg.put("content", m.content());
            messages.add(msg);
        }
        // 多模态图片：挂到最近一条 user 消息上（Ollama /api/chat 协议 images 字段）
        if (turn.images() != null && !turn.images().isEmpty()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("user".equals(messages.get(i).get("role"))) {
                    messages.get(i).put("images", turn.images());
                    break;
                }
            }
        }
        return messages;
    }

    private Map<String, Object> resolveOptions(ChatTurn turn) {
        Map<String, Object> raw = turn.options();
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", number(raw, "temperature", defaultOptions.temperature()));
        options.put("top_p", number(raw, "top_p", defaultOptions.topP()));
        options.put("top_k", integer(raw, "top_k", defaultOptions.topK()));
        options.put("num_predict", integer(raw, "max_tokens", defaultOptions.maxTokens()));
        options.put("repeat_penalty", number(raw, "repeat_penalty", defaultOptions.repeatPenalty()));
        options.put("num_ctx", integer(raw, "num_ctx", defaultOptions.numCtx()));
        if (defaultOptions.numGpu() >= 0) {
            options.put("num_gpu", integer(raw, "num_gpu", defaultOptions.numGpu()));
        }
        return options;
    }
}

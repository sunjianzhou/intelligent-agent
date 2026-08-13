package com.intelligent.agent.web.ai.llm.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.intelligent.agent.web.ai.llm.AbstractHttpLlmProvider;
import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmResponse;
import com.intelligent.agent.web.ai.llm.LlmProviderException;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.llm.OllamaOptions;
import com.intelligent.agent.web.ai.tool.ToolCall;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolSchemas;
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

    @Override
    public Mono<LlmResponse> completeWithTools(ChatTurn turn, List<ToolDefinition> tools) {
        return completeBody(chatRequest(turn, false, tools)).map(body -> {
            try {
                JsonNode root = MAPPER.readTree(body);
                String content = root.path("message").path("content").asText("").trim();
                List<ToolCall> calls = new ArrayList<>();
                JsonNode toolCalls = root.path("message").path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        String name = tc.path("function").path("name").asText("");
                        if (name.isEmpty()) {
                            continue;
                        }
                        calls.add(ToolCall.of(name,
                                parseArguments(tc.path("function").path("arguments"))));
                    }
                }
                return new LlmResponse(content, calls);
            } catch (Exception e) {
                throw new LlmProviderException(redact(e.getMessage()), e);
            }
        });
    }

    private HttpRequest chatRequest(ChatTurn turn, boolean stream) {
        return chatRequest(turn, stream, null);
    }

    private HttpRequest chatRequest(ChatTurn turn, boolean stream, List<ToolDefinition> tools) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", resolveModel(turn));
            payload.put("messages", toMessages(turn));
            payload.put("stream", stream);
            payload.put("keep_alive", keepAliveValue());
            payload.put("options", resolveOptions(turn));
            if (tools != null && !tools.isEmpty()) {
                payload.put("tools", ToolSchemas.toPayload(tools));
            }
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
            // 原生工具调用：归一化结构 → Ollama 原生格式（仅 function，去掉 id）
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                List<Map<String, Object>> ollamaCalls = new ArrayList<>();
                for (Map<String, Object> tc : m.toolCalls()) {
                    Object fn = tc.get("function");
                    if (fn instanceof Map<?, ?> fnMap) {
                        ollamaCalls.add(Map.of("function", fnMap));
                    }
                }
                if (!ollamaCalls.isEmpty()) {
                    msg.put("tool_calls", ollamaCalls);
                }
            }
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

    /** Ollama 原生 tool_calls arguments：对象或 JSON 字符串都兼容。 */
    private static Map<String, Object> parseArguments(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (node.isObject()) {
            return MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() { });
        }
        if (node.isTextual()) {
            try {
                JsonNode parsed = MAPPER.readTree(node.asText());
                if (parsed.isObject()) {
                    return MAPPER.convertValue(parsed, new TypeReference<Map<String, Object>>() { });
                }
            } catch (Exception ignored) {
                // 非 JSON 参数文本，忽略
            }
        }
        return Map.of();
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

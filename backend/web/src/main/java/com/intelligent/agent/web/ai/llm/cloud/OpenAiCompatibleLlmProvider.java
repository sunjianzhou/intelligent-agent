package com.intelligent.agent.web.ai.llm.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.intelligent.agent.web.ai.llm.AbstractHttpLlmProvider;
import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmResponse;
import com.intelligent.agent.web.ai.llm.LlmProviderException;
import com.intelligent.agent.web.ai.llm.ModelEvent;
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
 * OpenAI 兼容云端 provider（OpenAI / DeepSeek / 智谱 / 阿里云等）：
 * POST /chat/completions，SSE 行协议逐 token 产出事件。
 * 请求体与 Python {@code services/openai_provider.py} 保持一致。
 */
public class OpenAiCompatibleLlmProvider extends AbstractHttpLlmProvider {

    private volatile String baseUrl;
    private volatile String apiKey;
    private volatile String defaultModel;

    public OpenAiCompatibleLlmProvider(String baseUrl, String apiKey,
                                       String defaultModel, Duration timeout) {
        super(timeout, apiKey);
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
    }

    /** 仅当 base_url / api_key / model 全部配置非空时才参与云端路由。 */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank()
                && defaultModel != null && !defaultModel.isBlank();
    }

    /** 运行期热切换云端配置（CloudService 激活时调用），使激活立即对路由生效。 */
    public void configure(String baseUrl, String apiKey, String defaultModel) {
        this.baseUrl = stripTrailingSlash(baseUrl == null ? "" : baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.defaultModel = defaultModel == null ? "" : defaultModel;
        addRedactionSecret(this.apiKey);
    }

    /** 停用云端：清空配置，路由回落到本地 Ollama。 */
    public void clearConfig() {
        this.baseUrl = "";
        this.apiKey = "";
        this.defaultModel = "";
    }

    @Override
    public String name() {
        return "openai_compatible";
    }

    @Override
    public Flux<ModelEvent> stream(ChatTurn turn) {
        return streamLines(chatRequest(turn, true), (line, sink) -> {
            String payload = line;
            if (payload.startsWith("data:")) {
                payload = payload.substring(5).trim();
            }
            if (payload.equals("[DONE]")) {
                sink.next(ModelEvent.done(Map.of()));
                return true;
            }
            if (payload.isEmpty() || !payload.startsWith("{")) {
                return false;
            }
            try {
                JsonNode node = MAPPER.readTree(payload);
                String token = node.path("choices").path(0).path("delta").path("content").asText("");
                if (!token.isEmpty()) {
                    sink.next(ModelEvent.token(token));
                }
                return false;
            } catch (Exception e) {
                sink.next(ModelEvent.error("invalid stream chunk: " + redact(e.getMessage())));
                return true;
            }
        });
    }

    @Override
    public Mono<String> complete(ChatTurn turn) {
        return completeBody(chatRequest(turn, false)).map(body -> {
            try {
                return MAPPER.readTree(body)
                        .path("choices").path(0).path("message").path("content").asText("").trim();
            } catch (Exception e) {
                throw new LlmProviderException(redact(e.getMessage()), e);
            }
        });
    }

    @Override
    public Mono<LlmResponse> completeWithTools(ChatTurn turn, List<ToolDefinition> tools) {
        return completeBody(chatRequest(turn, false, tools)).map(body -> {
            try {
                JsonNode message = MAPPER.readTree(body).path("choices").path(0).path("message");
                String content = message.path("content").asText("").trim();
                List<ToolCall> calls = new ArrayList<>();
                JsonNode toolCalls = message.path("tool_calls");
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
            payload.put("temperature", number(turn.options(), "temperature", 0.7));
            payload.put("max_tokens", integer(turn.options(), "max_tokens", 2048));
            payload.put("stream", stream);
            if (tools != null && !tools.isEmpty()) {
                payload.put("tools", ToolSchemas.toPayload(tools));
            }
            String body = MAPPER.writeValueAsString(payload);
            return jsonRequest(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "text/event-stream")
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

    private List<Map<String, Object>> toMessages(ChatTurn turn) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage m : turn.messages()) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", m.role());
            if ("tool".equals(m.role())) {
                msg.put("content", m.content());
                if (m.toolCallId() != null && !m.toolCallId().isBlank()) {
                    msg.put("tool_call_id", m.toolCallId());
                }
            } else {
                msg.put("content", m.content());
            }
            // 原生工具调用：归一化结构 → OpenAI 格式（id/type/function + arguments JSON 字符串）
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                List<Map<String, Object>> openAiCalls = new ArrayList<>();
                for (Map<String, Object> tc : m.toolCalls()) {
                    Object fnRaw = tc.get("function");
                    if (!(fnRaw instanceof Map<?, ?> fnMap)) {
                        continue;
                    }
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", String.valueOf(fnMap.get("name")));
                    Object args = fnMap.get("arguments");
                    if (args instanceof Map<?, ?> argsMap) {
                        try {
                            function.put("arguments", MAPPER.writeValueAsString(argsMap));
                        } catch (Exception e) {
                            function.put("arguments", "");
                        }
                    } else {
                        function.put("arguments", args == null ? "" : String.valueOf(args));
                    }
                    Map<String, Object> call = new LinkedHashMap<>();
                    call.put("id", tc.get("id") == null ? "call_default" : tc.get("id"));
                    call.put("type", "function");
                    call.put("function", function);
                    openAiCalls.add(call);
                }
                if (!openAiCalls.isEmpty()) {
                    msg.put("tool_calls", openAiCalls);
                }
            }
            messages.add(msg);
        }
        // 多模态图片：OpenAI 兼容协议 content 改为多段数组，image_url 使用 data URL
        if (turn.images() != null && !turn.images().isEmpty()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("user".equals(messages.get(i).get("role"))) {
                    List<Map<String, Object>> parts = new ArrayList<>();
                    parts.add(Map.of("type", "text", "text",
                            String.valueOf(messages.get(i).get("content"))));
                    for (String image : turn.images()) {
                        parts.add(Map.of("type", "image_url", "image_url",
                                Map.of("url", "data:image/jpeg;base64," + image)));
                    }
                    messages.get(i).put("content", parts);
                    break;
                }
            }
        }
        return messages;
    }

    /** OpenAI 兼容 arguments：JSON 字符串（标准）或对象（部分兼容实现）。 */
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
}

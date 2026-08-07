package com.intelligent.agent.web.ai.llm.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.intelligent.agent.web.ai.llm.AbstractHttpLlmProvider;
import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProviderException;
import com.intelligent.agent.web.ai.llm.ModelEvent;
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

    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;

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

    private HttpRequest chatRequest(ChatTurn turn, boolean stream) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", resolveModel(turn));
            payload.put("messages", toMessages(turn));
            payload.put("temperature", number(turn.options(), "temperature", 0.7));
            payload.put("max_tokens", integer(turn.options(), "max_tokens", 2048));
            payload.put("stream", stream);
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
            msg.put("content", m.content());
            messages.add(msg);
        }
        return messages;
    }
}

package com.intelligent.agent.web.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

/**
 * 基于 JDK HttpClient 的阻塞式 HTTP LLM provider 基类，把行协议
 * （Ollama NDJSON / OpenAI 兼容 SSE）映射为响应式 {@link Flux}。
 * <p>
 * 阻塞 IO 统一放到 boundedElastic 调度器，避免占用事件循环线程；
 * 凭据在异常消息中一律脱敏。
 */
public abstract class AbstractHttpLlmProvider implements LlmProvider {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected final HttpClient httpClient;
    private final Duration requestTimeout;
    private final List<String> redactionSecrets;

    protected AbstractHttpLlmProvider(Duration requestTimeout, String... secretsToRedact) {
        this.requestTimeout = requestTimeout;
        this.redactionSecrets = List.of(secretsToRedact);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    protected HttpRequest.Builder jsonRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(requestTimeout);
    }

    /** 逐行消费流式响应；handler 返回 true 表示流已结束。 */
    protected Flux<ModelEvent> streamLines(HttpRequest request, LineHandler handler) {
        return Flux.<ModelEvent>create(sink -> {
            try {
                HttpResponse<Stream<String>> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    sink.error(new LlmProviderException(
                            "provider returned HTTP " + response.statusCode()));
                    return;
                }
                try (Stream<String> lines = response.body()) {
                    for (var it = lines.iterator(); it.hasNext(); ) {
                        if (sink.isCancelled()) {
                            return;
                        }
                        String line = it.next();
                        if (line.isBlank()) {
                            continue;
                        }
                        if (handler.consume(line, sink)) {
                            sink.complete();
                            return;
                        }
                    }
                }
                sink.complete();
            } catch (Exception e) {
                if (!sink.isCancelled()) {
                    sink.error(new LlmProviderException(redact(e.getMessage()), e));
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 非流式完整响应体。 */
    protected Mono<String> completeBody(HttpRequest request) {
        return Mono.fromCallable(() -> {
            try {
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new LlmProviderException(
                            "provider returned HTTP " + response.statusCode());
                }
                return response.body();
            } catch (LlmProviderException e) {
                throw e;
            } catch (Exception e) {
                throw new LlmProviderException(redact(e.getMessage()), e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    protected String redact(String message) {
        if (message == null) {
            return "unknown provider error";
        }
        String out = message;
        for (String secret : redactionSecrets) {
            if (secret != null && !secret.isBlank()) {
                out = out.replace(secret, "***");
            }
        }
        return out;
    }

    protected static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String out = url.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    protected static double number(java.util.Map<String, Object> raw, String key, double fallback) {
        Object v = raw.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    protected static int integer(java.util.Map<String, Object> raw, String key, int fallback) {
        Object v = raw.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    @FunctionalInterface
    protected interface LineHandler {
        /** 处理一行流式数据；返回 true 表示流已结束。 */
        boolean consume(String line, FluxSink<ModelEvent> sink);
    }
}

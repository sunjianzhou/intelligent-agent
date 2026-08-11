package com.intelligent.agent.web.infrastructure.vectorstore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实 embedding 服务（Ollama {@code /api/embed}），n-gram 哈希兜底。
 * <p>
 * TODO-110 Task 5：语义缓存 / 向量记忆从 n-gram 哈希近似升级为真实嵌入模型
 * （默认 {@code nomic-embed-text}，768 维）。Ollama 不可用时自动回退到
 * {@link TextEmbedding} 的 n-gram 哈希（128 维），调用方通过余弦相似度的
 * 维度守卫避免混用导致崩溃。
 * <p>
 * 线程安全：{@link ConcurrentHashMap} 缓存 + 不可变向量，可被多请求共享。
 */
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CACHE_SIZE = 512;

    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final boolean enabled;
    private final HttpClient httpClient;
    private final Map<String, double[]> cache = new ConcurrentHashMap<>();
    /** 首次成功后锁定真实模式，避免后续静默降级造成频繁重试；仅用于日志节流。*/
    private volatile boolean realMode;

    public EmbeddingService(String baseUrl, String model, Duration timeout, boolean enabled) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model == null || model.isBlank() ? "nomic-embed-text" : model.trim();
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        this.enabled = enabled;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 单条文本嵌入；远程失败或未启用时回退 n-gram 哈希。*/
    public double[] embed(String text) {
        String normalized = normalize(text);
        double[] cached = cache.get(normalized);
        if (cached != null) {
            return cached;
        }
        if (enabled) {
            List<double[]> fetched = embedRemote(List.of(normalized));
            if (!fetched.isEmpty() && fetched.get(0) != null) {
                double[] vector = fetched.get(0);
                cachePut(normalized, vector);
                return vector;
            }
        }
        return TextEmbedding.embed(text);
    }

    /** 批量嵌入，单次 Ollama 调用；失败项回退 n-gram 哈希。*/
    public List<double[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(texts.size());
        for (String text : texts) {
            normalized.add(normalize(text));
        }
        List<double[]> out = new ArrayList<>(normalized.size());
        List<String> missing = new ArrayList<>();
        List<Integer> missingIndexes = new ArrayList<>();
        for (int i = 0; i < normalized.size(); i++) {
            double[] cached = cache.get(normalized.get(i));
            if (cached != null) {
                out.add(cached);
            } else {
                out.add(null);
                missing.add(normalized.get(i));
                missingIndexes.add(i);
            }
        }
        if (enabled && !missing.isEmpty()) {
            List<double[]> fetched = embedRemote(missing);
            for (int i = 0; i < missingIndexes.size(); i++) {
                double[] vector = fetched.get(i);
                if (vector != null) {
                    out.set(missingIndexes.get(i), vector);
                    cachePut(missing.get(i), vector);
                }
            }
        }
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) == null) {
                out.set(i, TextEmbedding.embed(normalized.get(i)));
            }
        }
        return out;
    }

    /** 余弦相似度（含维度守卫，避免真实/兜底向量混用时崩溃）。*/
    public double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private List<double[]> embedRemote(List<String> inputs) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("input", inputs.size() == 1 ? inputs.get(0) : inputs);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/embed"))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("embedding provider returned HTTP " + response.statusCode());
            }
            JsonNode node = MAPPER.readTree(response.body());
            JsonNode embeddings = node.path("embeddings");
            if (!embeddings.isArray() || embeddings.size() != inputs.size()) {
                throw new IllegalStateException("unexpected embedding response shape");
            }
            List<double[]> result = new ArrayList<>(inputs.size());
            for (JsonNode embedding : embeddings) {
                double[] vector = new double[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = embedding.get(i).asDouble();
                }
                result.add(vector);
            }
            realMode = true;
            return result;
        } catch (Exception e) {
            if (!realMode) {
                log.warn("embedding service unavailable ({}), falling back to n-gram hash",
                        safeMessage(e));
            }
            List<double[]> fallback = new ArrayList<>(inputs.size());
            for (int i = 0; i < inputs.size(); i++) {
                fallback.add(null);
            }
            return fallback;
        }
    }

    private void cachePut(String key, double[] vector) {
        if (cache.size() >= MAX_CACHE_SIZE) {
            cache.clear();
        }
        cache.put(key, vector);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String out = url.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String safeMessage(Throwable e) {
        if (e == null || e.getMessage() == null) {
            return "unknown error";
        }
        return e.getMessage();
    }
}

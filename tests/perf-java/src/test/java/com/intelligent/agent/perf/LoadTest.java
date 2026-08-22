package com.intelligent.agent.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可重复压测/基线工具（@Tag("perf")，默认不进 CI）：
 * <ul>
 *   <li>health：GET /api/health，纯 HTTP 吞吐基线（不触 LLM）；</li>
 *   <li>chat：POST /api/chat 非流式真实推理（异步 REST 路径，含推理闸门排队）；</li>
 *   <li>stream：POST /api/chat/stream SSE 流式，额外记录首 token 延迟。</li>
 * </ul>
 * 输出 P50/P90/P95/P99、RPS、错误率到 target/perf-report/perf-&lt;ts&gt;.json，
 * 可选 -Dperf.saveBaseline=path 保存基线、-Dperf.baseline=path 与历史基线对比。
 * <p>
 * 运行（需后端 + Ollama）：
 * <pre>
 * cd backend/web &amp;&amp; mvnw.cmd -f ../../tests/perf-java/pom.xml test \
 *   -Dgroups=perf -DexcludedGroups= -Dperf.concurrency=4 -Dperf.chatIterations=4 \
 *   -Dperf.streamIterations=4 -Dperf.saveBaseline=perf-baseline.json
 * </pre>
 */
@Tag("perf")
class LoadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BASE_URL = System.getProperty("perf.baseUrl", "http://localhost:8080");
    private static final String USERNAME = System.getProperty("perf.username", "admin");
    private static final String PASSWORD = System.getProperty("perf.password", "admin123");
    private static final int CONCURRENCY = intProp("perf.concurrency", 4);
    private static final int HEALTH_ITERATIONS = intProp("perf.healthIterations", 300);
    private static final int CHAT_ITERATIONS = intProp("perf.chatIterations", 4);
    private static final int STREAM_ITERATIONS = intProp("perf.streamIterations", 4);
    private static final int REQUEST_TIMEOUT_SECONDS = intProp("perf.requestTimeoutSeconds", 300);
    private static final String BASELINE = System.getProperty("perf.baseline");
    private static final String SAVE_BASELINE = System.getProperty("perf.saveBaseline");
    private static final String CHAT_PROMPT = "请用一句话介绍你自己";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void runLoadScenarios() throws Exception {
        String token = login();
        Assumptions.assumeTrue(token != null,
                "登录失败，跳过压测（检查后端是否运行、perf.username/password 是否正确）");
        authToken = token;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generated_at", Instant.now().toString());
        report.put("base_url", BASE_URL);
        report.put("concurrency", CONCURRENCY);
        report.put("prompt", CHAT_PROMPT);
        report.put("config", Map.of(
                "healthIterations", HEALTH_ITERATIONS,
                "chatIterations", CHAT_ITERATIONS,
                "streamIterations", STREAM_ITERATIONS,
                "requestTimeoutSeconds", REQUEST_TIMEOUT_SECONDS));

        Map<String, Object> scenarios = new LinkedHashMap<>();
        scenarios.put("health", runScenario("health", HEALTH_ITERATIONS, this::healthRequest));
        scenarios.put("chat", runScenario("chat", CHAT_ITERATIONS, this::chatRequest));
        scenarios.put("stream", runScenario("stream", STREAM_ITERATIONS, this::streamRequest));
        report.put("scenarios", scenarios);

        Path reportDir = Path.of("target", "perf-report");
        Files.createDirectories(reportDir);
        Path reportFile = reportDir.resolve("perf-" + System.currentTimeMillis() + ".json");
        Files.writeString(reportFile, MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(report), StandardCharsets.UTF_8);
        System.out.println("\n[perf] report: " + reportFile.toAbsolutePath());
        printSummary(scenarios);

        if (SAVE_BASELINE != null && !SAVE_BASELINE.isBlank()) {
            Path baselineFile = Path.of(SAVE_BASELINE);
            Path parent = baselineFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(baselineFile, MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report), StandardCharsets.UTF_8);
            System.out.println("[perf] baseline saved: " + baselineFile.toAbsolutePath());
        }
        if (BASELINE != null && !BASELINE.isBlank() && Files.exists(Path.of(BASELINE))) {
            compareBaseline(report, Path.of(BASELINE));
        }
    }

    private Map<String, Object> runScenario(String name, int iterations, RequestCall call)
            throws InterruptedException {
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        List<Long> firstToken = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        Semaphore gate = new Semaphore(CONCURRENCY);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch done = new CountDownLatch(iterations);
        long startNanos = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            pool.submit(() -> {
                try {
                    gate.acquire();
                    long start = System.nanoTime();
                    long firstTokenMs = call.run();
                    latencies.add((System.nanoTime() - start) / 1_000_000);
                    if (firstTokenMs >= 0) {
                        firstToken.add(firstTokenMs);
                    }
                    ok.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                    System.err.println("[perf] " + name + " 请求失败: " + e.getMessage());
                } finally {
                    gate.release();
                    done.countDown();
                }
            });
        }
        done.await(REQUEST_TIMEOUT_SECONDS * 2L, TimeUnit.SECONDS);
        pool.shutdownNow();

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", iterations);
        result.put("ok", ok.get());
        result.put("errors", errors.get());
        result.put("rps", elapsedMs <= 0 ? 0 : round1(ok.get() * 1000.0 / elapsedMs));
        result.put("elapsed_ms", elapsedMs);
        result.put("mean_ms", round1(sorted.stream().mapToLong(Long::longValue).average().orElse(0)));
        result.put("p50_ms", percentile(sorted, 0.50));
        result.put("p90_ms", percentile(sorted, 0.90));
        result.put("p95_ms", percentile(sorted, 0.95));
        result.put("p99_ms", percentile(sorted, 0.99));
        result.put("max_ms", sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1));
        if (!firstToken.isEmpty()) {
            List<Long> sortedFirst = new ArrayList<>(firstToken);
            sortedFirst.sort(Long::compareTo);
            result.put("first_token_p50_ms", percentile(sortedFirst, 0.50));
            result.put("first_token_p95_ms", percentile(sortedFirst, 0.95));
        }
        return result;
    }

    private long healthRequest() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/health"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("health HTTP " + res.statusCode());
        }
        return -1;
    }

    private long chatRequest() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/chat"))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token())
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(Map.of(
                        "message", CHAT_PROMPT,
                        "use_tools", false,
                        "use_memory", false))))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("chat HTTP " + res.statusCode());
        }
        String response = chatResponse(res.body());
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("chat 响应为空");
        }
        return -1;
    }

    /** SSE 流式：返回首事件延迟 ms（无事件返回 -1）。 */
    private long streamRequest() throws Exception {
        long sentAt = System.nanoTime();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/chat/stream"))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + token())
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(Map.of(
                        "message", CHAT_PROMPT,
                        "use_tools", false,
                        "use_memory", false))))
                .build();
        HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        long firstEventAt = -1;
        if (res.statusCode() != 200) {
            res.body().close();
            throw new IllegalStateException("stream HTTP " + res.statusCode());
        }
        StringBuilder tokens = new StringBuilder();
        boolean done = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REQUEST_TIMEOUT_SECONDS);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("stream 读取超时（" + REQUEST_TIMEOUT_SECONDS + "s）");
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                if (firstEventAt < 0) {
                    firstEventAt = System.nanoTime();
                }
                String json = line.substring(5).trim();
                if (json.isEmpty()) {
                    continue;
                }
                Map<?, ?> event = MAPPER.readValue(json, Map.class);
                Object typeObj = event.get("type");
                String type = typeObj == null ? "" : String.valueOf(typeObj);
                if ("token".equals(type)) {
                    Object dataObj = event.get("data");
                    if (dataObj != null) {
                        tokens.append(String.valueOf(dataObj));
                    }
                } else if ("error".equals(type)) {
                    throw new IllegalStateException("stream error 事件: " + event.get("data"));
                } else if ("done".equals(type)) {
                    done = true;
                    break;
                }
            }
        }
        if (!done) {
            throw new IllegalStateException("stream 未收到 done 事件");
        }
        String text = tokens.toString();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        if (text.isBlank()) {
            throw new IllegalStateException("stream 响应为空");
        }
        return firstEventAt < 0 ? -1 : (firstEventAt - sentAt) / 1_000_000;
    }

    private String login() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/auth/login"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(
                        Map.of("username", USERNAME, "password", PASSWORD))))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            return null;
        }
        Map<?, ?> body = MAPPER.readValue(res.body(), Map.class);
        Object token = body.get("token");
        return token == null ? null : String.valueOf(token);
    }

    private volatile String authToken;

    private String token() {
        String t = authToken;
        if (t == null) {
            synchronized (this) {
                t = authToken;
                if (t == null) {
                    try {
                        t = login();
                    } catch (Exception e) {
                        throw new IllegalStateException("登录失败", e);
                    }
                    authToken = t;
                }
            }
        }
        return t;
    }

    private static String chatResponse(String body) {
        try {
            Map<?, ?> parsed = MAPPER.readValue(body, Map.class);
            Object data = parsed.get("data");
            if (data instanceof Map<?, ?> d) {
                Object response = d.get("response");
                if (response != null) {
                    return String.valueOf(response);
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static void printSummary(Map<String, Object> scenarios) {
        System.out.println("\n========== perf summary ==========");
        scenarios.forEach((name, raw) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) raw;
            System.out.printf("[%-7s] ok=%s/%s errors=%s rps=%s p50=%sms p95=%sms p99=%sms max=%sms",
                    name, s.get("ok"), s.get("requests"), s.get("errors"),
                    s.get("rps"), s.get("p50_ms"), s.get("p95_ms"), s.get("p99_ms"), s.get("max_ms"));
            if (s.get("first_token_p50_ms") != null) {
                System.out.printf(" firstTokenP50=%sms", s.get("first_token_p50_ms"));
            }
            System.out.println();
        });
        System.out.println("==================================");
    }

    private static void compareBaseline(Map<String, Object> report, Path baselineFile) throws Exception {
        Map<?, ?> baseline = MAPPER.readValue(Files.readString(baselineFile), Map.class);
        System.out.println("\n========== baseline comparison (" + baselineFile + ") ==========");
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) report.get("scenarios");
        @SuppressWarnings("unchecked")
        Map<String, Object> old = (Map<String, Object>) baseline.get("scenarios");
        for (String name : current.keySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> now = (Map<String, Object>) current.get(name);
            @SuppressWarnings("unchecked")
            Map<String, Object> before = old == null ? null : (Map<String, Object>) old.get(name);
            if (before == null) {
                System.out.println("[perf] " + name + ": 基线中不存在，跳过");
                continue;
            }
            double p95Now = num(now.get("p95_ms"));
            double p95Before = num(before.get("p95_ms"));
            double rpsNow = num(now.get("rps"));
            double rpsBefore = num(before.get("rps"));
            double p95Delta = p95Before <= 0 ? 0 : (p95Now - p95Before) / p95Before * 100;
            double rpsDelta = rpsBefore <= 0 ? 0 : (rpsNow - rpsBefore) / rpsBefore * 100;
            String warn = p95Delta > 20 ? "  <-- P95 劣化 >20%，请关注" : "";
            System.out.printf("[%-7s] p95 %sms -> %sms (%+.1f%%) | rps %.1f -> %.1f (%+.1f%%)%s%n",
                    name, p95Before, p95Now, p95Delta, rpsBefore, rpsNow, rpsDelta, warn);
        }
        System.out.println("==================================");
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static int intProp(String key, int fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double num(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    @FunctionalInterface
    private interface RequestCall {
        /** 返回首事件延迟 ms；非流式返回 -1。 */
        long run() throws Exception;
    }
}

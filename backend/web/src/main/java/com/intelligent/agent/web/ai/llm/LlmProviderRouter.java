package com.intelligent.agent.web.ai.llm;

import com.intelligent.agent.web.ai.llm.cloud.OpenAiCompatibleLlmProvider;
import com.intelligent.agent.web.ai.llm.circuit.CircuitBreakerRegistry;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;

/**
 * LLM provider 路由器：按请求模型解析实际 provider。
 * <p>
 * 云端（OpenAI 兼容）配置齐全且请求模型命中云模型清单（或未指定模型）时走云端，
 * 其余情况一律回退本地 Ollama。userId 预留用于后续 per-user provider 覆盖，
 * 当前不参与判定。
 */
public class LlmProviderRouter {

    private final LlmProvider local;
    private final OpenAiCompatibleLlmProvider cloud;
    private final Set<String> cloudModels = ConcurrentHashMap.newKeySet();
    private final CircuitBreakerRegistry breakerRegistry;
    private final InferenceGate gate;
    private final Duration queueTimeout;
    /** R-02 fallback 链：别名 → 有序模型列表（含首模型；"default" 为兜底链）。 */
    private final Map<String, List<String>> fallbackChains;
    private final Duration fallbackBudget;
    private final FallbackRateLimiter fallbackRateLimiter;

    public LlmProviderRouter(LlmProvider local,
                             OpenAiCompatibleLlmProvider cloud,
                             List<String> cloudModels) {
        this(local, cloud, cloudModels, null);
    }

    public LlmProviderRouter(LlmProvider local,
                             OpenAiCompatibleLlmProvider cloud,
                             List<String> cloudModels,
                             CircuitBreakerRegistry breakerRegistry) {
        this(local, cloud, cloudModels, breakerRegistry, null);
    }

    public LlmProviderRouter(LlmProvider local,
                             OpenAiCompatibleLlmProvider cloud,
                             List<String> cloudModels,
                             CircuitBreakerRegistry breakerRegistry,
                             InferenceGate gate) {
        this(local, cloud, cloudModels, breakerRegistry, gate,
                ConcurrencyLimitedLlmProvider.DEFAULT_QUEUE_TIMEOUT);
    }

    public LlmProviderRouter(LlmProvider local,
                             OpenAiCompatibleLlmProvider cloud,
                             List<String> cloudModels,
                             CircuitBreakerRegistry breakerRegistry,
                             InferenceGate gate,
                             Duration queueTimeout) {
        this(local, cloud, cloudModels, breakerRegistry, gate, queueTimeout,
                Map.of(), Duration.ofSeconds(60), new FallbackRateLimiter(
                        FallbackRateLimiter.DEFAULT_DAILY_LIMIT));
    }

    /** R-02：fallback 链在 router 层实现（包裹完整 gate+breaker provider）。 */
    public LlmProviderRouter(LlmProvider local,
                             OpenAiCompatibleLlmProvider cloud,
                             List<String> cloudModels,
                             CircuitBreakerRegistry breakerRegistry,
                             InferenceGate gate,
                             Duration queueTimeout,
                             Map<String, List<String>> fallbackChains,
                             Duration fallbackBudget,
                             FallbackRateLimiter fallbackRateLimiter) {
        this.local = Objects.requireNonNull(local, "local provider is required");
        this.cloud = cloud;
        this.breakerRegistry = breakerRegistry;
        this.gate = gate;
        this.queueTimeout = queueTimeout == null
                ? ConcurrencyLimitedLlmProvider.DEFAULT_QUEUE_TIMEOUT : queueTimeout;
        this.fallbackChains = fallbackChains == null
                ? Map.of() : fallbackChains.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> e.getValue() == null ? List.of() : List.copyOf(e.getValue())));
        this.fallbackBudget = fallbackBudget == null
                ? Duration.ofSeconds(60) : fallbackBudget;
        this.fallbackRateLimiter = fallbackRateLimiter == null
                ? new FallbackRateLimiter(FallbackRateLimiter.DEFAULT_DAILY_LIMIT)
                : fallbackRateLimiter;
        if (cloudModels != null) {
            for (String model : cloudModels) {
                registerCloudModel(model);
            }
        }
    }

    public LlmProvider forUser(String userId, String requestedModel) {
        String model = requestedModel == null ? "" : requestedModel.trim();
        LlmProvider target;
        if (cloud != null && cloud.isConfigured()
                && (model.isEmpty() || cloudModels.contains(model))) {
            target = cloud;
        } else {
            target = local;
        }
        // 并发闸门在内、熔断在外：熔断打开时快速失败，不必先排队等槽位；
        // 未注入 gate（如纯单元测试）时行为与之前完全一致。
        if (gate != null) {
            // 按模型分槽：显式模型名各自独立计数，默认（空）走公共槽位
            String gateKey = model.isEmpty() ? "" : model;
            target = new ConcurrencyLimitedLlmProvider(target, gate, queueTimeout, gateKey);
        }
        if (breakerRegistry != null) {
            // G6：按模型熔断（未指定模型时按 provider 名），熔断打开时快速失败
            return breakerRegistry.wrap(model.isEmpty() ? target.name() : model, target);
        }
        return target;
    }

    /** 运行期注册云端模型名（CloudService 激活时调用），使路由立即识别。 */
    public void registerCloudModel(String model) {
        if (model != null && !model.isBlank()) {
            cloudModels.add(model.trim());
        }
    }

    /** 停用云端时清空运行期注册的模型名（静态配置不受影响，但无配置时路由回落本地）。 */
    public void clearCloudModels() {
        cloudModels.clear();
    }

    // ── R-02 模型 fallback 链 ────────────────────────────────────────────

    /**
     * 解析请求模型的 fallback 链（含首模型，有序）。
     * 规则：显式模型命中链 → 用该链；否则用 "default" 兜底链；
     * 请求模型不在链中时前置（先试用户指定模型，再按链降级）。无可用链返回 null。
     */
    public List<String> resolveFallbackChain(String requestedModel) {
        String key = requestedModel == null ? "" : requestedModel.trim();
        List<String> chain = fallbackChains.get(key);
        if (chain == null || chain.isEmpty()) {
            chain = fallbackChains.get("default");
        }
        if (chain == null || chain.isEmpty()) {
            return null;
        }
        List<String> attempts = new ArrayList<>();
        if (!key.isEmpty()) {
            attempts.add(key);
        }
        for (String model : chain) {
            if (model == null || model.isBlank()) {
                continue;
            }
            if (!attempts.contains(model)) {
                attempts.add(model);
            }
        }
        if (attempts.size() <= 1) {
            return null;
        }
        return attempts;
    }

    /**
     * 非流式（complete / completeWithTools）带 fallback。
     * 熔断 OPEN 直接切换（不消耗日额度）；仅超时 / 5xx / 429 消耗一次降级额度；
     * 每次尝试独立超时，整链延迟预算上限 {@code fallbackBudget}。
     */
    public Mono<FallbackResult> completeWithFallback(String userId, String requestedModel,
                                                     ChatTurn turn, List<ToolDefinition> tools,
                                                     FallbackTracker tracker) {
        List<String> chain = resolveFallbackChain(requestedModel);
        if (chain == null) {
            return attemptComplete(userId, requestedModel, turn, tools)
                    .map(response -> new FallbackResult(
                            response, requestedModel, false, 0));
        }
        long start = System.currentTimeMillis();
        return attemptCompleteChain(userId, chain, 0, turn, tools, tracker, start);
    }

    /** 流式（stream）带 fallback：降级时在事件流内联发 model_fallback，再重试下一模型。 */
    public Flux<ModelEvent> streamWithFallback(String userId, String requestedModel,
                                               ChatTurn turn, FallbackTracker tracker) {
        List<String> chain = resolveFallbackChain(requestedModel);
        if (chain == null) {
            return forUser(userId, requestedModel).stream(turn);
        }
        long start = System.currentTimeMillis();
        AtomicBoolean anyToken = new AtomicBoolean();
        return attemptStream(userId, chain, 0, turn, tracker, start, anyToken);
    }

    private Mono<LlmResponse> attemptComplete(String userId, String model, ChatTurn turn,
                                              List<ToolDefinition> tools) {
        LlmProvider provider = forUser(userId, model);
        if (tools != null && !tools.isEmpty()) {
            return provider.completeWithTools(turn, tools);
        }
        return provider.complete(turn)
                .map(content -> new LlmResponse(content, List.of()));
    }

    private Mono<FallbackResult> attemptCompleteChain(String userId, List<String> chain, int index,
                                                      ChatTurn turn, List<ToolDefinition> tools,
                                                      FallbackTracker tracker, long start) {
        String model = chain.get(index);
        Duration remaining = remainingBudget(start);
        if (remaining == null) {
            return Mono.error(new LlmProviderException(
                    "fallback chain budget exceeded (" + fallbackBudget.toSeconds() + "s)"));
        }
        ChatTurn attemptTurn = forAttempt(turn, model, remaining);
        return attemptComplete(userId, model, attemptTurn, tools)
                .map(response -> new FallbackResult(response, model, index > 0,
                        System.currentTimeMillis() - start))
                .onErrorResume(e -> {
                    if (index + 1 >= chain.size() || !shouldRetry(e)) {
                        return Mono.error(e);
                    }
                    if (isTransientFailure(e) && !fallbackRateLimiter.tryAcquire()) {
                        return Mono.error(e);
                    }
                    if (tracker != null) {
                        tracker.markFallback(chain.get(index + 1), reasonOf(e));
                    }
                    return attemptCompleteChain(userId, chain, index + 1,
                            turn, tools, tracker, start);
                });
    }

    private Flux<ModelEvent> attemptStream(String userId, List<String> chain, int index,
                                           ChatTurn turn, FallbackTracker tracker,
                                           long start, AtomicBoolean anyToken) {
        String model = chain.get(index);
        Duration remaining = remainingBudget(start);
        if (remaining == null) {
            return Flux.error(new LlmProviderException(
                    "fallback chain budget exceeded (" + fallbackBudget.toSeconds() + "s)"));
        }
        ChatTurn attemptTurn = forAttempt(turn, model, remaining);
        Flux<ModelEvent> attempt = forUser(userId, model).stream(attemptTurn)
                .doOnNext(event -> {
                    if ("token".equals(event.type()) || "tool_call".equals(event.type())
                            || "tool_call_start".equals(event.type())) {
                        anyToken.set(true);
                    }
                });
        if (index + 1 >= chain.size()) {
            return attempt;
        }
        return attempt.onErrorResume(e -> {
            if (anyToken.get() || !shouldRetry(e)) {
                return Flux.error(e);
            }
            if (isTransientFailure(e) && !fallbackRateLimiter.tryAcquire()) {
                return Flux.error(e);
            }
            String from = chain.get(index);
            String to = chain.get(index + 1);
            String reason = reasonOf(e);
            if (tracker != null) {
                tracker.markFallback(to, reason);
            }
            return Flux.concat(
                    Flux.just(ModelEvent.modelFallback(from, to, reason)),
                    attemptStream(userId, chain, index + 1, turn, tracker, start, anyToken));
        });
    }

    /** 每次尝试：改写 turn.model 为链上模型 + 按整链剩余预算收紧 chat_timeout。 */
    private static ChatTurn forAttempt(ChatTurn turn, String model, Duration remaining) {
        Map<String, Object> options = new LinkedHashMap<>(turn.options());
        long seconds = Math.max(1, remaining.getSeconds());
        Object existing = options.get("chat_timeout");
        if (existing instanceof Number n && n.longValue() > 0 && n.longValue() < seconds) {
            seconds = n.longValue();
        }
        options.put("chat_timeout", seconds);
        return new ChatTurn(turn.userId(), model, turn.messages(), options, turn.images());
    }

    private Duration remainingBudget(long start) {
        long elapsed = System.currentTimeMillis() - start;
        long remainMs = fallbackBudget.toMillis() - elapsed;
        return remainMs <= 0 ? null : Duration.ofMillis(remainMs);
    }

    /** 熔断 OPEN 或超时 / 5xx / 429 才允许降级（其余错误原样抛出）。 */
    static boolean shouldRetry(Throwable e) {
        return isCircuitOpen(e) || isTransientFailure(e);
    }

    static boolean isCircuitOpen(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains("circuit breaker open")) {
                return true;
            }
        }
        return false;
    }

    static boolean isTransientFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            if (message.contains("timed out") || message.contains("timeout")) {
                return true;
            }
            if (message.matches(".*provider returned HTTP (5\\d\\d|429).*")) {
                return true;
            }
        }
        return false;
    }

    static String reasonOf(Throwable e) {
        if (isCircuitOpen(e)) {
            return "circuit_open";
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.http.HttpTimeoutException) {
                return "timeout";
            }
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            if (message.contains("timed out") || message.contains("timeout")) {
                return "timeout";
            }
            if (message.matches(".*provider returned HTTP (5\\d\\d|429).*")) {
                return message.contains("429") ? "http_429" : "http_5xx";
            }
        }
        return "transient";
    }

    /** 非流式 fallback 结果：实际生效模型 + 是否降级 + 整链耗时。 */
    public record FallbackResult(LlmResponse response, String effectiveModel,
                                 boolean fallbackUsed, long elapsedMs) {
    }

    /** 请求级 fallback 追踪：记录是否降级与实际生效模型（trace / 语义缓存跳过用）。 */
    public static final class FallbackTracker {
        private final AtomicReference<String> effectiveModel;
        private volatile boolean used;
        private volatile String reason;

        public FallbackTracker(String requestedModel) {
            this.effectiveModel = new AtomicReference<>(
                    requestedModel == null || requestedModel.isBlank() ? "" : requestedModel);
        }

        void markFallback(String to, String reason) {
            this.effectiveModel.set(to);
            this.used = true;
            this.reason = reason;
        }

        public boolean used() {
            return used;
        }

        public String effectiveModel() {
            return effectiveModel.get();
        }

        public String reason() {
            return reason;
        }
    }
}

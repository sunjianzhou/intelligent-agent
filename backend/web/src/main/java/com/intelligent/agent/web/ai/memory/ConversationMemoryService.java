package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.ai.agent.AgentRequestContext;
import com.intelligent.agent.web.ai.llm.ChatMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话记忆服务：短期历史（按用户 deque，TTL 24h，最近 100 条）、
 * 长期 RAG 召回、项目上下文、五轮蒸馏、十轮摘要、语义缓存读写。
 */
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);
    public static final Duration SHORT_TERM_TTL = Duration.ofHours(24);
    public static final int SHORT_TERM_MAX_SIZE = 100;
    public static final int RAG_TOP_K = 3;
    // G5 分层记忆：按层配额（episodic=summary 类，semantic=fact 类）
    public static final int EPISODIC_TOP_K = 2;
    public static final int SEMANTIC_TOP_K = 3;
    public static final double CACHE_SIMILARITY_THRESHOLD = 0.8;
    /** R-01 上下文压缩时最多注入的最近摘要条数（summary 类型按创建时间倒序）。 */
    public static final int COMPACTION_SUMMARY_LIMIT = 2;

    /** R-04 聊天内纠错识别器（无状态，单例复用）。 */
    private static final MemoryCorrectionService CORRECTION = new MemoryCorrectionService();

    private final MemoryRepository memoryRepository;
    private final SemanticResponseCache semanticCache;
    private final MemoryDistillationService distiller;
    private final int episodicTopK;
    private final int semanticTopK;
    private final Executor background;

    private static volatile ExecutorService sharedBackground;

    private static ExecutorService sharedBackground() {
        ExecutorService current = sharedBackground;
        if (current == null) {
            synchronized (ConversationMemoryService.class) {
                current = sharedBackground;
                if (current == null) {
                    current = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "memory-shared-worker");
                        t.setDaemon(true);
                        return t;
                    });
                    sharedBackground = current;
                }
            }
        }
        return current;
    }

    private final Map<String, Deque<StampedMessage>> shortTerm = new ConcurrentHashMap<>();
    private final Map<String, Integer> turnCounts = new ConcurrentHashMap<>();
    /** 项目级提取轮次（按 userId|projectId 计数）。*/
    private final Map<String, Integer> projectTurnCounts = new ConcurrentHashMap<>();
    /** 撤回后从长期检索中排除的内容（按用户隔离）。 */
    private final Map<String, java.util.Set<String>> excludedLongTerm = new ConcurrentHashMap<>();

    public ConversationMemoryService(MemoryRepository memoryRepository,
                                     SemanticResponseCache semanticCache,
                                     MemoryDistillationService distiller) {
        this(memoryRepository, semanticCache, distiller, EPISODIC_TOP_K, SEMANTIC_TOP_K,
                sharedBackground());
    }

    public ConversationMemoryService(MemoryRepository memoryRepository,
                                     SemanticResponseCache semanticCache,
                                     MemoryDistillationService distiller,
                                     Executor background) {
        this(memoryRepository, semanticCache, distiller, EPISODIC_TOP_K, SEMANTIC_TOP_K, background);
    }

    public ConversationMemoryService(MemoryRepository memoryRepository,
                                     SemanticResponseCache semanticCache,
                                     MemoryDistillationService distiller,
                                     int episodicTopK, int semanticTopK) {
        this(memoryRepository, semanticCache, distiller, episodicTopK, semanticTopK,
                sharedBackground());
    }

    public ConversationMemoryService(MemoryRepository memoryRepository,
                                     SemanticResponseCache semanticCache,
                                     MemoryDistillationService distiller,
                                     int episodicTopK, int semanticTopK,
                                     Executor background) {
        this.memoryRepository = memoryRepository;
        this.semanticCache = semanticCache;
        this.distiller = distiller;
        this.episodicTopK = Math.max(1, episodicTopK);
        this.semanticTopK = Math.max(1, semanticTopK);
        this.background = background;
    }

    /** 加载单次请求的记忆上下文；useMemory=false 时返回空上下文。 */
    public AgentContext loadContext(AgentRequestContext ctx) {
        if (!ctx.useMemory()) {
            return AgentContext.empty();
        }
        String userId = effectiveUserId(ctx.userId());
        List<ChatMessage> history = historyMessages(userId);

        String queryText = ctx.message() == null ? "" : ctx.message();
        // G5 分层：episodic = summary 类；semantic = 其余类型（fact/knowledge/遗留等）
        List<MemoryRecord> episodic = queryText.isBlank()
                ? List.of()
                : filterExcluded(userId, memoryRepository.search(
                        MemorySearchQuery.builder(userId, queryText, episodicTopK)
                                .type("summary").build()));
        List<MemoryRecord> semantic = queryText.isBlank()
                ? List.of()
                : filterExcluded(userId, memoryRepository.search(
                        MemorySearchQuery.builder(userId, queryText, semanticTopK)
                                .excludeTypes(Set.of("summary")).build()));
        List<MemoryRecord> recall = mergeLayers(episodic, semantic);

        String projectContext = projectContext(ctx);

        OptionalCacheResult cache = lookupCache(ctx);

        return new AgentContext(history, recall, episodic, semantic,
                projectContext, cache.answer());
    }

    /** 合并分层召回（按 id 去重，保持 episodic 在前）。 */
    private static List<MemoryRecord> mergeLayers(List<MemoryRecord> episodic,
                                                  List<MemoryRecord> semantic) {
        List<MemoryRecord> merged = new ArrayList<>(episodic);
        java.util.Set<String> ids = episodic.stream().map(MemoryRecord::id)
                .collect(Collectors.toSet());
        for (MemoryRecord record : semantic) {
            if (ids.add(record.id())) {
                merged.add(record);
            }
        }
        return merged;
    }

    /** 记录一轮对话：写短期历史、按 5/10 轮触发蒸馏与摘要、回写语义缓存。 */
    public void recordTurn(AgentRequestContext ctx, String answer) {
        doRecordTurn(ctx, answer, false);
    }

    /** R-02：skipCacheWrite=true 时跳过语义缓存回写（fallback 跨模型作答不污染缓存）。 */
    public void recordTurn(AgentRequestContext ctx, String answer, boolean skipCacheWrite) {
        if (!skipCacheWrite) {
            // 兼容子类覆写的 2 参入口（如测试 StubMemoryService）
            recordTurn(ctx, answer);
            return;
        }
        doRecordTurn(ctx, answer, true);
    }

    private void doRecordTurn(AgentRequestContext ctx, String answer, boolean skipCacheWrite) {
        if (!ctx.useMemory()) {
            return;
        }
        String userId = effectiveUserId(ctx.userId());
        append(userId, "user", ctx.message());
        if (answer != null && !answer.isBlank()) {
            append(userId, "assistant", answer);
        }

        int turns = turnCounts.merge(userId, 1, Integer::sum);
        List<ChatMessage> history = historyMessages(userId);
        // 蒸馏/摘要/项目提取是 LLM 重活：丢到后台执行器执行，避免阻塞聊天响应收尾路径
        // （每 5/10/8 轮触发一次最长 30s 的 LLM 调用，不再占用请求线程/推理闸门排队）。
        if (turns % distiller.interval() == 0) {
            runAsync(() -> distiller.distill(userId, ctx.model(), history, memoryRepository));
        }
        if (turns % distiller.summaryInterval() == 0) {
            runAsync(() -> distiller.summarize(userId, history, memoryRepository));
        }
        if (ctx.projectId() != null && !ctx.projectId().isBlank()) {
            String projectKey = userId + "|" + ctx.projectId();
            int projectTurns = projectTurnCounts.merge(projectKey, 1, Integer::sum);
            if (projectTurns % distiller.projectInterval() == 0) {
                runAsync(() -> distiller.extractProjectContext(
                        userId, ctx.projectId(), history, memoryRepository));
            }
        }

        if (!skipCacheWrite && answer != null && !answer.isBlank()
                && ctx.message() != null && !ctx.message().isBlank()) {
            semanticCache.put(userId, ctx.persona(), ctx.model(), ctx.message(), answer);
        }
    }

    /** 后台任务有界队列满时丢弃并告警，绝不让提取任务反压到请求线程。*/
    private void runAsync(Runnable task) {
        try {
            background.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("记忆后台任务队列已满，跳过本次提取");
        }
    }

    /** 某用户短期记忆条数（/api/memory 统计用）。 */
    public int shortTermCount(String userId) {
        return historyMessages(effectiveUserId(userId)).size();
    }

    /** 手动触发蒸馏 + 摘要（/api/memory/distill，2026-08-15 补齐对齐 Python）。 */
    public int distillNow(String userId) {
        String key = effectiveUserId(userId);
        List<ChatMessage> history = historyMessages(key);
        int records = 0;
        records += distiller.distill(key, null, history, memoryRepository);
        int before = memoryRepository.count(
                MemorySearchQuery.builder(key, "", 100000).type("summary").build());
        distiller.summarize(key, history, memoryRepository);
        int after = memoryRepository.count(
                MemorySearchQuery.builder(key, "", 100000).type("summary").build());
        records += (after - before);
        return records;
    }

    // ── R-01 上下文压缩（历史窗口滚动 + 摘要注入 + 无摘要降级） ─────────

    /**
     * 最近会话摘要（summary 类型，按创建时间倒序，不依赖查询文本）。
     * 上下文压缩时注入用；无摘要返回空列表。
     */
    public List<MemoryRecord> recentSummaries(AgentRequestContext ctx) {
        if (!ctx.useMemory()) {
            return List.of();
        }
        String userId = effectiveUserId(ctx.userId());
        List<MemoryRecord> summaries = memoryRepository.list(
                MemorySearchQuery.builder(userId, "", COMPACTION_SUMMARY_LIMIT)
                        .type("summary").build());
        return filterExcluded(userId, summaries);
    }

    /**
     * R-04 聊天内纠错：消息命中"删掉/修改/忘了你记的 X"类指令时直接修正记忆并返回回执；
     * 非纠错消息返回 empty。修正动作走软删除（可恢复），检索层下一轮不再召回旧事实。
     */
    public java.util.Optional<String> applyCorrection(AgentRequestContext ctx) {
        if (!ctx.useMemory() || ctx.message() == null || ctx.message().isBlank()) {
            return java.util.Optional.empty();
        }
        MemoryCorrectionService.CorrectionRequest request = CORRECTION.detect(ctx.message());
        if (request == null) {
            return java.util.Optional.empty();
        }
        String reply = CORRECTION.apply(
                effectiveUserId(ctx.userId()), request, memoryRepository);
        return java.util.Optional.of(reply);
    }

    /**
     * R-01 历史压缩：
     * <ul>
     *   <li>历史估算 ≤ 预算 → 原样返回（不裁剪）；</li>
     *   <li>超限且有最近摘要 → 滚动窗口保留最近 N 条 + 注入 [RECENT SESSION SUMMARY]；</li>
     *   <li>超限但无摘要 → <b>铁律：不裁剪</b>（宁可超窗告警，不静默丢上下文）。</li>
     * </ul>
     * 返回 {@link CompactionResult}，调用方据 {@code summaryUsed} / {@code dropped}
     * 决定是否写 trace 告警。
     */
    public CompactionResult compactHistory(AgentRequestContext ctx,
                                           List<ChatMessage> history,
                                           ContextBudget.Plan plan) {
        if (!ctx.useMemory() || history == null || history.isEmpty() || plan == null) {
            return CompactionResult.unchanged(history);
        }
        int historyTokens = ContextBudget.estimateMessages(history);
        if (historyTokens <= plan.historyTokens()) {
            return CompactionResult.unchanged(history);
        }
        List<MemoryRecord> summaries = recentSummaries(ctx);
        if (summaries.isEmpty()) {
            // 降级铁律：无可用的最近摘要时不得裁剪历史
            return new CompactionResult(history, 0, false, true, historyTokens);
        }

        ChatMessage summaryMessage = ChatMessage.system(
                recallSection("[RECENT SESSION SUMMARY]", summaries));
        int summaryTokens = ContextBudget.estimateMessage(summaryMessage);
        int remain = Math.max(1, plan.historyTokens() - summaryTokens);

        List<ChatMessage> kept = keepRecentWithinBudget(history, remain);
        int dropped = history.size() - kept.size();
        List<ChatMessage> compacted = new ArrayList<>(kept.size() + 1);
        compacted.add(summaryMessage);
        compacted.addAll(kept);
        return new CompactionResult(compacted, dropped, true, false, historyTokens);
    }

    /** 从最旧到最新逐条评估：预算内保留最近消息；开头若为 assistant/tool 则一并丢弃，保持 user 开头。 */
    private static List<ChatMessage> keepRecentWithinBudget(List<ChatMessage> history, int budget) {
        List<ChatMessage> kept = new ArrayList<>();
        int used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage message = history.get(i);
            int tokens = ContextBudget.estimateMessage(message);
            if (!kept.isEmpty() && used + tokens > budget) {
                continue;
            }
            kept.add(0, message);
            used += tokens;
        }
        while (!kept.isEmpty() && !"user".equals(kept.get(0).role())) {
            kept.remove(0);
        }
        return kept;
    }

    /** 记忆召回 / 摘要注入的文本段格式（与 AgentOrchestrator 的召回段一致）。 */
    private static String recallSection(String header, List<MemoryRecord> records) {
        StringBuilder sb = new StringBuilder(header).append('\n');
        for (MemoryRecord record : records) {
            sb.append("- ").append(record.content()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 某用户短期记忆消息列表（/api/memory/list 用）。 */
    public List<ChatMessage> shortTermMessages(String userId) {
        return historyMessages(effectiveUserId(userId));
    }

    /** 清空某用户短期记忆（/api/memory 清空用）。 */
    public void clearShortTerm(String userId) {
        shortTerm.remove(effectiveUserId(userId));
        turnCounts.remove(effectiveUserId(userId));
        excludedLongTerm.remove(effectiveUserId(userId));
        projectTurnCounts.keySet().removeIf(key -> key.startsWith(effectiveUserId(userId) + "|"));
    }

    /**
     * 撤回级联（对齐 Python retract）：按内容从短期记忆 deque 中删除消息，
     * 并加入长期检索排除集，使后续 RAG 召回不再命中被撤回的内容。
     *
     * @return 实际从短期记忆中删除的消息条数
     */
    public int purgeMessages(String userId, java.util.List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }
        String key = effectiveUserId(userId);
        java.util.Set<String> targets = contents.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::strip)
                .collect(java.util.stream.Collectors.toSet());
        if (targets.isEmpty()) {
            return 0;
        }
        Deque<StampedMessage> deque = shortTerm.get(key);
        int removed = 0;
        if (deque != null) {
            Iterator<StampedMessage> it = deque.iterator();
            while (it.hasNext()) {
                if (targets.contains(it.next().content().strip())) {
                    it.remove();
                    removed++;
                }
            }
        }
        excludeFromLongTerm(userId, contents);
        return removed;
    }

    /** 将内容加入长期检索排除集（撤回后不再被语义召回）。 */
    public void excludeFromLongTerm(String userId, java.util.List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return;
        }
        excludedLongTerm.computeIfAbsent(effectiveUserId(userId), k ->
                java.util.concurrent.ConcurrentHashMap.newKeySet())
                .addAll(contents.stream()
                        .filter(c -> c != null && !c.isBlank())
                        .map(String::strip)
                        .toList());
    }

    // ── 短期历史 ──────────────────────────────────────────────

    private void append(String userId, String role, String content) {
        shortTerm.computeIfAbsent(userId, k -> new ArrayDeque<>())
                .addLast(new StampedMessage(role, content == null ? "" : content, Instant.now()));
        evict(userId);
    }

    private void evict(String userId) {
        Deque<StampedMessage> deque = shortTerm.get(userId);
        if (deque == null) {
            return;
        }
        Instant cutoff = Instant.now().minus(SHORT_TERM_TTL);
        Iterator<StampedMessage> it = deque.iterator();
        while (it.hasNext()) {
            StampedMessage message = it.next();
            if (message.createdAt().isBefore(cutoff)) {
                it.remove();
            }
        }
        while (deque.size() > SHORT_TERM_MAX_SIZE) {
            deque.pollFirst();
        }
    }

    private List<ChatMessage> historyMessages(String userId) {
        Deque<StampedMessage> deque = shortTerm.get(userId);
        if (deque == null) {
            return List.of();
        }
        evict(userId);
        return deque.stream()
                .map(message -> new ChatMessage(message.role(), message.content()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<MemoryRecord> filterExcluded(String userId, List<MemoryRecord> records) {
        java.util.Set<String> excluded = excludedLongTerm.get(userId);
        if (excluded == null || excluded.isEmpty() || records == null || records.isEmpty()) {
            return records;
        }
        return records.stream()
                .filter(record -> !excluded.contains(record.content().strip()))
                .toList();
    }

    // ── 长期检索 ──────────────────────────────────────────────

    private String projectContext(AgentRequestContext ctx) {
        if (ctx.projectId() == null || ctx.projectId().isBlank()) {
            return "";
        }
        String userId = effectiveUserId(ctx.userId());
        List<MemoryRecord> projectRecords = memoryRepository.search(
                MemorySearchQuery.builder(userId, ctx.message() == null ? "" : ctx.message(), 5)
                        .projectId(ctx.projectId())
                        .build());
        return filterExcluded(userId, projectRecords).stream()
                .map(MemoryRecord::content)
                .collect(Collectors.joining("\n"));
    }

    // ── 语义缓存 ──────────────────────────────────────────────

    private OptionalCacheResult lookupCache(AgentRequestContext ctx) {
        String question = ctx.message();
        if (question == null || question.isBlank()) {
            return OptionalCacheResult.empty();
        }
        java.util.Optional<String> exact = semanticCache.get(
                ctx.userId(), ctx.persona(), ctx.model(), question);
        if (exact.isPresent()) {
            return OptionalCacheResult.of(exact.get());
        }
        java.util.Optional<String> similar = semanticCache.findSimilar(
                ctx.userId(), ctx.persona(), ctx.model(), question, CACHE_SIMILARITY_THRESHOLD);
        return OptionalCacheResult.ofNullable(similar.orElse(null));
    }

    private record StampedMessage(String role, String content, Instant createdAt) {
    }

    private static String effectiveUserId(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    private record OptionalCacheResult(java.util.Optional<String> answer) {
        static OptionalCacheResult empty() {
            return new OptionalCacheResult(java.util.Optional.empty());
        }

        static OptionalCacheResult of(String answer) {
            return new OptionalCacheResult(java.util.Optional.of(answer));
        }

        static OptionalCacheResult ofNullable(String answer) {
            return new OptionalCacheResult(java.util.Optional.ofNullable(answer));
        }
    }

    /** R-01 压缩结果：压缩后的历史 + 丢弃条数 + 是否注入摘要 + 是否超窗未裁剪。 */
    public record CompactionResult(
            List<ChatMessage> history,
            int dropped,
            boolean summaryUsed,
            boolean overBudgetNoSummary,
            int historyTokens) {

        static CompactionResult unchanged(List<ChatMessage> history) {
            return new CompactionResult(history == null ? List.of() : history,
                    0, false, false,
                    ContextBudget.estimateMessages(history));
        }
    }
}

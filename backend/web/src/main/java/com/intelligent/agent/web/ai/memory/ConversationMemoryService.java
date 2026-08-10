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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 会话记忆服务：短期历史（按用户 deque，TTL 24h，最近 100 条）、
 * 长期 RAG 召回、项目上下文、五轮蒸馏、十轮摘要、语义缓存读写。
 */
public class ConversationMemoryService {

    public static final Duration SHORT_TERM_TTL = Duration.ofHours(24);
    public static final int SHORT_TERM_MAX_SIZE = 100;
    public static final int RAG_TOP_K = 3;
    public static final double CACHE_SIMILARITY_THRESHOLD = 0.8;

    private final MemoryRepository memoryRepository;
    private final SemanticResponseCache semanticCache;
    private final MemoryDistillationService distiller;

    private final Map<String, Deque<StampedMessage>> shortTerm = new ConcurrentHashMap<>();
    private final Map<String, Integer> turnCounts = new ConcurrentHashMap<>();
    /** 撤回后从长期检索中排除的内容（按用户隔离）。 */
    private final Map<String, java.util.Set<String>> excludedLongTerm = new ConcurrentHashMap<>();

    public ConversationMemoryService(MemoryRepository memoryRepository,
                                     SemanticResponseCache semanticCache,
                                     MemoryDistillationService distiller) {
        this.memoryRepository = memoryRepository;
        this.semanticCache = semanticCache;
        this.distiller = distiller;
    }

    /** 加载单次请求的记忆上下文；useMemory=false 时返回空上下文。 */
    public AgentContext loadContext(AgentRequestContext ctx) {
        if (!ctx.useMemory()) {
            return AgentContext.empty();
        }
        String userId = effectiveUserId(ctx.userId());
        List<ChatMessage> history = historyMessages(userId);

        List<MemoryRecord> recall = ctx.message() == null || ctx.message().isBlank()
                ? List.of()
                : filterExcluded(userId, memoryRepository.search(userId, ctx.message(), RAG_TOP_K));

        String projectContext = projectContext(ctx);

        OptionalCacheResult cache = lookupCache(ctx);

        return new AgentContext(history, recall, projectContext, cache.answer());
    }

    /** 记录一轮对话：写短期历史、按 5/10 轮触发蒸馏与摘要、回写语义缓存。 */
    public void recordTurn(AgentRequestContext ctx, String answer) {
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
        if (turns % distiller.interval() == 0) {
            distiller.distill(userId, history, memoryRepository);
        }
        if (turns % distiller.summaryInterval() == 0) {
            distiller.summarize(userId, history, memoryRepository);
        }

        if (answer != null && !answer.isBlank() && ctx.message() != null && !ctx.message().isBlank()) {
            semanticCache.put(userId, ctx.persona(), ctx.model(), ctx.message(), answer);
        }
    }

    /** 某用户短期记忆条数（/api/memory 统计用）。 */
    public int shortTermCount(String userId) {
        return historyMessages(effectiveUserId(userId)).size();
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
}

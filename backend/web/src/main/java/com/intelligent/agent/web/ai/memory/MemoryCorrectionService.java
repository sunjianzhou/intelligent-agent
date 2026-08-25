package com.intelligent.agent.web.ai.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R-04 聊天内记忆纠错：识别"删掉/修改/忘了你记的 X"类指令，直接对记忆做
 * 软删除（失效）或更新（失效旧记录 + 写入新记录），并回执确认文案。
 *
 * <p>删除一律走软删除（{@link MemoryRepository#invalidate}），可恢复，
 * 避免误删不可逆；检索层自动过滤失效记录，下一轮不再召回旧事实。</p>
 */
public class MemoryCorrectionService {

    /** 删除：删掉/移除/忘掉 + (你记的|你的记忆|记忆里的) + 目标。 */
    private static final Pattern DELETE_DIRECT = Pattern.compile(
            "(?:删除|删掉|移除|忘掉|忘了)\\s*(?:你记的|你记住的|你的记忆|记忆(?:里|中)?的?)\\s*"
                    + "[：:，,]?\\s*(.+)$");

    /** 删除：把 X (从|在)(你的)?记忆里 (删除|删掉|移除|忘掉)。 */
    private static final Pattern DELETE_WRAP = Pattern.compile(
            "把[“”\"'「」]?(.+?)[“”\"'「」]?(?:从|在)(?:你的)?(?:记忆|长期记忆)(?:里|中)?"
                    + "(?:删除|删掉|移除|忘掉|忘了)\\s*$");

    /** 更新：把 X (改成|改为|更正为|纠正为|更新为) Y。 */
    private static final Pattern UPDATE_REPLACE = Pattern.compile(
            "把[“”\"'「」]?(.+?)[“”\"'「」]?(?:改成|改为|更正为|纠正为|更新为)"
                    + "[“”\"'「」]?(.+?)[。！!]?$");

    /** 更新：记住 X (其实|实际上|并不是|不是) Y。 */
    private static final Pattern UPDATE_REMEMBER = Pattern.compile(
            "记住?[：:]?\\s*(.+?)(?:其实是|实际上是|并不是|并不是|不是)(.+?)[。！!]?$");

    private static final int SEARCH_LIMIT = 8;

    public record CorrectionRequest(Kind kind, String target, String replacement) {
        enum Kind { DELETE, UPDATE }
    }

    /** 识别纠错指令；未命中返回 null（非纠错消息）。 */
    public CorrectionRequest detect(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String text = message.trim();

        Matcher update = UPDATE_REPLACE.matcher(text);
        if (update.matches()) {
            String target = clean(update.group(1));
            String replacement = clean(update.group(2));
            if (!target.isEmpty() && !replacement.isEmpty()) {
                return new CorrectionRequest(CorrectionRequest.Kind.UPDATE, target, replacement);
            }
        }
        Matcher remember = UPDATE_REMEMBER.matcher(text);
        if (remember.matches()) {
            String target = clean(remember.group(1));
            String replacement = clean(remember.group(2));
            if (!target.isEmpty() && !replacement.isEmpty()) {
                return new CorrectionRequest(CorrectionRequest.Kind.UPDATE, target, replacement);
            }
        }

        Matcher direct = DELETE_DIRECT.matcher(text);
        if (direct.matches()) {
            String target = clean(direct.group(1));
            if (!target.isEmpty()) {
                return new CorrectionRequest(CorrectionRequest.Kind.DELETE, target, null);
            }
        }
        Matcher wrap = DELETE_WRAP.matcher(text);
        if (wrap.matches()) {
            String target = clean(wrap.group(1));
            if (!target.isEmpty()) {
                return new CorrectionRequest(CorrectionRequest.Kind.DELETE, target, null);
            }
        }
        return null;
    }

    /** 执行纠错：软删除命中记录（UPDATE 额外写入新记录），返回给用户的回执文案。 */
    public String apply(String userId, CorrectionRequest request, MemoryRepository repository) {
        List<MemoryRecord> matched = findMatches(userId, request.target(), repository);
        int invalidated = 0;
        for (MemoryRecord record : matched) {
            if (repository.invalidate(userId, record.id(),
                    "聊天纠错:" + request.kind().name().toLowerCase())) {
                invalidated++;
            }
        }
        if (request.kind() == CorrectionRequest.Kind.UPDATE && !request.replacement().isBlank()) {
            repository.upsert(new MemoryRecord(
                    "mem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                    userId, request.replacement(), null, null, "fact",
                    Map.of("source", "correction"), 0.7));
        }
        if (invalidated == 0) {
            return request.kind() == CorrectionRequest.Kind.UPDATE
                    ? "已修正记忆：将「" + request.target() + "」更新为「"
                            + request.replacement() + "」（未找到旧记忆，已直接写入新记忆）"
                    : "未找到与「" + request.target() + "」相关的记忆，无需删除";
        }
        if (request.kind() == CorrectionRequest.Kind.UPDATE) {
            return "已修正记忆：删除了 " + invalidated + " 条与「" + request.target()
                    + "」相关的旧记忆，并更新为「" + request.replacement() + "」";
        }
        return "已修正记忆：删除了 " + invalidated + " 条与「" + request.target() + "」相关的记忆";
    }

    /** 检索召回 + 内容包含匹配（双向），只处理置信度高的命中，避免误删。 */
    private static List<MemoryRecord> findMatches(String userId, String target,
                                                  MemoryRepository repository) {
        List<MemoryRecord> candidates = repository.search(userId, target, SEARCH_LIMIT);
        if (candidates.isEmpty()) {
            return List.of();
        }
        String normalized = normalize(target);
        List<MemoryRecord> matched = new ArrayList<>();
        for (MemoryRecord record : candidates) {
            String content = normalize(record.content());
            if (content.isEmpty() || normalized.isEmpty()) {
                continue;
            }
            if (content.contains(normalized) || normalized.contains(content)) {
                matched.add(record);
            }
        }
        return matched;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[“”\"'「」、，,。！!？?\\s]+", "").trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }
}

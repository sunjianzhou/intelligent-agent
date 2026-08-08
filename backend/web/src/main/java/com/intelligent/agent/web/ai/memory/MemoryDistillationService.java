package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.ai.llm.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆蒸馏与会话摘要（Plan 2 / Task 2）。
 * <p>
 * 当前为确定性的规则式提取（无需 LLM 即可测试）：用户消息长度 >= 6 且
 * 非工具结果即视为候选事实；每 10 轮写入一条摘要记录。
 * 后续可替换为 LLM 提取而不改变调用契约。
 */
public class MemoryDistillationService {

    public static final int DEFAULT_INTERVAL = 5;
    public static final int DEFAULT_SUMMARY_INTERVAL = 10;

    private final int interval;
    private final int summaryInterval;

    public MemoryDistillationService() {
        this(DEFAULT_INTERVAL, DEFAULT_SUMMARY_INTERVAL);
    }

    public MemoryDistillationService(int interval, int summaryInterval) {
        this.interval = interval;
        this.summaryInterval = summaryInterval;
    }

    public int interval() {
        return interval;
    }

    public int summaryInterval() {
        return summaryInterval;
    }

    /** 规则式事实提取：非工具结果的用户消息，去重。 */
    public List<String> extractFacts(List<ChatMessage> history) {
        Set<String> facts = new LinkedHashSet<>();
        for (ChatMessage message : history) {
            if (!"user".equals(message.role())) {
                continue;
            }
            String content = message.content() == null ? "" : message.content().trim();
            if (content.length() < 6 || content.startsWith("[工具执行结果]")) {
                continue;
            }
            facts.add(content);
        }
        return new ArrayList<>(facts);
    }

    /** 蒸馏：把窗口内的用户消息作为事实写入长期记忆，返回写入条数。 */
    public int distill(String userId, List<ChatMessage> history, MemoryRepository repository) {
        List<String> facts = extractFacts(history);
        int stored = 0;
        for (String fact : facts) {
            repository.upsert(new MemoryRecord(
                    userId + "-fact-" + Integer.toHexString(fact.hashCode()),
                    userId, "事实: " + fact, null, null, "fact", Map.of("source", "distillation"), 0.7));
            stored++;
        }
        return stored;
    }

    /** 会话摘要：把最近窗口压缩为一条 summary 记录。 */
    public void summarize(String userId, List<ChatMessage> history, MemoryRepository repository) {
        if (history.isEmpty()) {
            return;
        }
        int windowStart = Math.max(0, history.size() - summaryInterval * 2);
        StringBuilder summary = new StringBuilder("会话摘要: ");
        for (ChatMessage message : history.subList(windowStart, history.size())) {
            String content = message.content() == null ? "" : message.content();
            if (content.length() > 40) {
                content = content.substring(0, 40) + "...";
            }
            summary.append('[').append("user".equals(message.role()) ? "用户" : "助手")
                    .append("] ").append(content).append(" ");
        }
        repository.upsert(new MemoryRecord(
                userId + "-summary-" + Long.toHexString(System.nanoTime()),
                userId, summary.toString(), null, null, "summary",
                Map.of("source", "session_summary"), 0.6));
    }
}

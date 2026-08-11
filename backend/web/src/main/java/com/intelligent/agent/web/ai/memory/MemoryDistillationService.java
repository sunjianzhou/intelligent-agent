package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.ai.llm.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 记忆蒸馏与会话摘要（Plan 2 / Task 2 + TODO-110 Task 5）。
 * <p>
 * 默认由 LLM 提取（可配置开关），失败时回滚规则式提取
 * （用户消息长度 >= 6 且不是工具结果即视为候选事实）；
 * 每 10 轮写入一条摘要记录；项目上下文每 8 轮由 LLM 提取。
 */
public class MemoryDistillationService {

    public static final int DEFAULT_INTERVAL = 5;
    public static final int DEFAULT_SUMMARY_INTERVAL = 10;
    public static final int DEFAULT_PROJECT_INTERVAL = 8;

    private static final String FACT_SYSTEM_PROMPT =
            "你是对话记忆蒸馏器。从对话中提取值得长期保存的用户事实：偏好、身份背景、重要决定、长期约定等。\n"
                    + "要求：\n"
                    + "1. 用简洁的中文陈述，一行一条，不编号，不重复原文；\n"
                    + "2. 只保留跨会话仍有价值的信息，忽略一次性寒暄和工具执行结果；\n"
                    + "3. 若没有值得记忆的事实，只输出 <NONE>。";

    private static final String PROJECT_SYSTEM_PROMPT =
            "你是项目上下文提取器。从对话中提取与该项目相关的持久上下文：需求、决定、约束、里程碑、命名约定、待办等。\n"
                    + "要求：\n"
                    + "1. 用简洁的中文陈述，一行一条，不编号；\n"
                    + "2. 只保留项目相关且后续仍有用的事实；\n"
                    + "3. 若没有，只输出 <NONE>。";

    private final int interval;
    private final int summaryInterval;
    private final int projectInterval;
    private final LlmExtractionService llmExtraction;

    public MemoryDistillationService() {
        this(DEFAULT_INTERVAL, DEFAULT_SUMMARY_INTERVAL);
    }

    public MemoryDistillationService(int interval, int summaryInterval) {
        this(interval, summaryInterval, DEFAULT_PROJECT_INTERVAL, null);
    }

    public MemoryDistillationService(int interval, int summaryInterval,
                                     int projectInterval, LlmExtractionService llmExtraction) {
        this.interval = interval;
        this.summaryInterval = summaryInterval;
        this.projectInterval = projectInterval > 0 ? projectInterval : DEFAULT_PROJECT_INTERVAL;
        this.llmExtraction = llmExtraction;
    }

    public int interval() {
        return interval;
    }

    public int summaryInterval() {
        return summaryInterval;
    }

    public int projectInterval() {
        return projectInterval;
    }

    /** 模型无关的事实提取（带 model）：优先 LLM 提取，失败时回滚规则。*/
    public List<String> extractFacts(String userId, String model, List<ChatMessage> history) {
        if (llmExtraction != null) {
            String conversation = buildConversationText(history, 24);
            if (!conversation.isBlank()) {
                Optional<String> extracted = llmExtraction.complete(
                        userId, model, FACT_SYSTEM_PROMPT, conversation);
                List<String> facts = parseLines(extracted.orElse(""));
                if (!facts.isEmpty()) {
                    return facts;
                }
            }
        }
        return ruleBasedFacts(history);
    }

    /** 兼容入口（不发 LLM）：规则式事实提取。*/
    public List<String> extractFacts(List<ChatMessage> history) {
        return ruleBasedFacts(history);
    }

    /** 蒸馏：把窗口内的事实写入长期记忆，返回写入条数。*/
    public int distill(String userId, List<ChatMessage> history, MemoryRepository repository) {
        return distill(userId, null, history, repository);
    }

    /** 蒸馏（带 model）：使用 LLM 或规则提取事实并写入。*/
    public int distill(String userId, String model,
                       List<ChatMessage> history, MemoryRepository repository) {
        List<String> facts = extractFacts(userId, model, history);
        int stored = 0;
        for (String fact : facts) {
            repository.upsert(new MemoryRecord(
                    userId + "-fact-" + Integer.toHexString(fact.hashCode()),
                    userId, "事实: " + fact, null, null, "fact",
                    Map.of("source", "distillation"), 0.7));
            stored++;
        }
        return stored;
    }

    /**
     * 项目上下文提取：每 projectInterval 轮由 LLM 把近期对话提取为项目级 nuggets
     * 并写入持久记录，loadContext 时通过项目 RAG 回调。LLM 失败时规则式回滚。
     */
    public void extractProjectContext(String userId, String projectId,
                                      List<ChatMessage> history, MemoryRepository repository) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        List<String> nuggets;
        if (llmExtraction != null) {
            String conversation = buildConversationText(history, 24);
            if (!conversation.isBlank()) {
                Optional<String> extracted = llmExtraction.complete(
                        userId, null, PROJECT_SYSTEM_PROMPT, conversation);
                nuggets = parseLines(extracted.orElse(""));
                if (!nuggets.isEmpty()) {
                    storeProjectNuggets(userId, projectId, nuggets, repository);
                    return;
                }
            }
        }
        nuggets = ruleBasedFacts(history);
        if (!nuggets.isEmpty()) {
            storeProjectNuggets(userId, projectId, nuggets, repository);
        }
    }

    private void storeProjectNuggets(String userId, String projectId, List<String> nuggets,
                                     MemoryRepository repository) {
        for (String nugget : nuggets) {
            repository.upsert(new MemoryRecord(
                    userId + "-proj-" + projectId + "-" + Integer.toHexString(nugget.hashCode()),
                    userId, nugget, null, projectId, "project",
                    Map.of("source", "project_extraction"), 0.8));
        }
    }

    private static List<String> ruleBasedFacts(List<ChatMessage> history) {
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

    /** 会话摘要：把最近窗口压缩为一条 summary 记录。*/
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

    private static String buildConversationText(List<ChatMessage> history, int maxMessages) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        List<ChatMessage> window = history.size() <= maxMessages
                ? history : history.subList(history.size() - maxMessages, history.size());
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : window) {
            String content = message.content() == null ? "" : message.content().strip();
            if (content.isBlank()) {
                continue;
            }
            String role = "user".equals(message.role()) ? "用户"
                    : ("assistant".equals(message.role()) ? "助手" : message.role());
            sb.append('[').append(role).append("] ").append(content).append('\n');
        }
        return sb.toString().strip();
    }

    private static List<String> parseLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String clean = line.trim();
            if (clean.isEmpty()) {
                continue;
            }
            clean = clean.replaceFirst("^(?:[-*•]|\\d+[.)、])\\s*", "").trim();
            if (clean.isBlank() || "<NONE>".equalsIgnoreCase(clean)) {
                continue;
            }
            if (clean.length() > 200) {
                clean = clean.substring(0, 200) + "...";
            }
            out.add(clean);
            if (out.size() >= 8) {
                break;
            }
        }
        return out;
    }
}

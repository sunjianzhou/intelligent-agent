package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.ai.llm.ChatMessage;

import java.util.List;
import java.util.Optional;

/**
 * 单次请求加载出的记忆上下文：
 * <ul>
 *   <li>history：短期会话历史（按用户，TTL 24h，最近 100 条）；</li>
 *   <li>longTermRecall：合并召回（episodic + semantic，兼容旧路径）；</li>
 *   <li>episodicRecall：情景记忆（summary 类记录，按层配额）；</li>
 *   <li>semanticRecall：语义记忆（fact 类记录，按层配额）；</li>
 *   <li>projectContext：项目级上下文摘要（仅当请求带 projectId）；</li>
 *   <li>cachedAnswer：语义缓存命中时直接复用，跳过 LLM。</li>
 * </ul>
 */
public record AgentContext(
        List<ChatMessage> history,
        List<MemoryRecord> longTermRecall,
        List<MemoryRecord> episodicRecall,
        List<MemoryRecord> semanticRecall,
        String projectContext,
        Optional<String> cachedAnswer) {

    public AgentContext {
        history = history == null ? List.of() : List.copyOf(history);
        longTermRecall = longTermRecall == null ? List.of() : List.copyOf(longTermRecall);
        episodicRecall = episodicRecall == null ? List.of() : List.copyOf(episodicRecall);
        semanticRecall = semanticRecall == null ? List.of() : List.copyOf(semanticRecall);
        projectContext = projectContext == null ? "" : projectContext;
        cachedAnswer = cachedAnswer == null ? Optional.empty() : cachedAnswer;
    }

    /** 无分层字段的便捷构造（保持旧调用点兼容，episodic/semantic 为空）。 */
    public AgentContext(List<ChatMessage> history, List<MemoryRecord> longTermRecall,
                        String projectContext, Optional<String> cachedAnswer) {
        this(history, longTermRecall, List.of(), List.of(), projectContext, cachedAnswer);
    }

    public static AgentContext empty() {
        return new AgentContext(List.of(), List.of(), List.of(), List.of(), "", Optional.empty());
    }

    public boolean usesMemory() {
        return !history.isEmpty() || !longTermRecall.isEmpty()
                || !episodicRecall.isEmpty() || !semanticRecall.isEmpty()
                || !projectContext.isBlank() || cachedAnswer.isPresent();
    }
}

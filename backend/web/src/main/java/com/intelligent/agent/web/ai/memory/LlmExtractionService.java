package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM 提取辅助服务：供记忆蒸馏 / 项目上下文提取复用。
 * <p>
 * TODO-110 Task 5：把规则式提取升级为 LLM 提取。同步阻塞（boundedElastic），
 * 带超时；任何失败都返回 {@link Optional#empty()}，由调用方回退规则式逻辑，
 * 保证模型环境不可用时功能不退化。
 */
public class LlmExtractionService {

    private static final Logger log = LoggerFactory.getLogger(LlmExtractionService.class);

    private final LlmProviderRouter router;
    private final String defaultModel;
    private final Duration timeout;
    private final boolean enabled;

    public LlmExtractionService(LlmProviderRouter router, String defaultModel,
                                Duration timeout, boolean enabled) {
        this.router = router;
        this.defaultModel = defaultModel == null || defaultModel.isBlank()
                ? "qwen2.5:7b" : defaultModel.trim();
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        this.enabled = enabled;
    }

    /**
     * 用 LLM 完成一次提取任务（低温度、限制输出长度）。
     *
     * @return 完整回复文本；未启用 / 超时 / 模型错误时为空。
     */
    public Optional<String> complete(String userId, String model,
                                     String systemPrompt, String userContent) {
        if (!enabled || systemPrompt == null || systemPrompt.isBlank()
                || userContent == null || userContent.isBlank()) {
            return Optional.empty();
        }
        String resolvedModel = model == null || model.isBlank() ? defaultModel : model.trim();
        try {
            ChatTurn turn = new ChatTurn(userId == null ? "" : userId, resolvedModel,
                    List.of(ChatMessage.system(systemPrompt), ChatMessage.user(userContent)),
                    Map.of("temperature", 0.2, "max_tokens", 1024));
            String answer = router.forUser(userId, resolvedModel).complete(turn).block(timeout);
            if (answer == null || answer.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(answer.trim());
        } catch (Exception e) {
            log.warn("LLM extraction failed ({}), caller should fall back to rule-based logic",
                    safeMessage(e));
            return Optional.empty();
        }
    }

    private static String safeMessage(Throwable e) {
        if (e == null || e.getMessage() == null) {
            return "unknown error";
        }
        return e.getMessage();
    }
}

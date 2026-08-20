package com.intelligent.agent.web.ai.agent.reflection;

import com.intelligent.agent.web.ai.agent.AgentRequestContext;
import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LLM 答案自检器（G6 reflection 后验默认实现）：
 * 低温度调用一次模型，对照用户请求 / 执行计划 / 工具结果检查草稿，
 * 输出修正后的最终答案；任何失败（超时/空回复/模型错误）都原样返回草稿。
 */
public class LlmAnswerReflector implements AnswerReflector {

    private static final Logger log = LoggerFactory.getLogger(LlmAnswerReflector.class);

    static final String SYSTEM_PROMPT =
            "你是一个严格的答案审校员。请检查草稿答案是否准确、完整、与工具执行结果一致，"
                    + "并纠正错误、补充关键遗漏。只输出最终答案本身，不要解释修改过程，"
                    + "不要输出任何工具调用。如果草稿已经正确，原样输出草稿。";

    private final LlmProviderRouter router;
    private final boolean enabled;
    private final Duration timeout;

    public LlmAnswerReflector(LlmProviderRouter router) {
        this(router, true, Duration.ofSeconds(30));
    }

    public LlmAnswerReflector(LlmProviderRouter router, boolean enabled, Duration timeout) {
        this.router = router;
        this.enabled = enabled;
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }

    @Override
    public String reflect(AgentRequestContext context, String draftAnswer,
                          List<String> toolResults, List<String> planSteps) {
        if (!enabled || draftAnswer == null || draftAnswer.isBlank()) {
            return draftAnswer;
        }
        try {
            ChatTurn turn = new ChatTurn(context.userId(), context.model(),
                    List.of(ChatMessage.system(SYSTEM_PROMPT),
                            ChatMessage.user(buildUserPrompt(context.message(),
                                    draftAnswer, toolResults, planSteps))),
                    Map.of("temperature", 0.2, "max_tokens", 2048));
            String revised = router.forUser(context.userId(), context.model())
                    .complete(turn).block(timeout);
            return clean(revised, draftAnswer);
        } catch (Exception e) {
            log.warn("reflection failed, keeping draft answer: {}", safeMessage(e));
            return draftAnswer;
        }
    }

    private static String clean(String revised, String draft) {
        if (revised == null || revised.isBlank()) {
            return draft;
        }
        String text = revised.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```(?:\\w+)?\\s*", "")
                    .replaceAll("\\s*```$", "")
                    .trim();
        }
        return text.isBlank() ? draft : text;
    }

    static String buildUserPrompt(String userRequest, String draft,
                                  List<String> toolResults, List<String> planSteps) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户请求：").append(userRequest == null ? "" : userRequest).append('\n');
        if (planSteps != null && !planSteps.isEmpty()) {
            sb.append("\n执行计划：\n");
            for (int i = 0; i < planSteps.size(); i++) {
                sb.append(i + 1).append(". ").append(planSteps.get(i)).append('\n');
            }
        }
        if (toolResults != null && !toolResults.isEmpty()) {
            sb.append("\n工具执行结果：\n");
            for (String result : toolResults) {
                sb.append("- ").append(result).append('\n');
            }
        }
        sb.append("\n草稿答案：\n").append(draft).append('\n');
        sb.append("\n请检查草稿答案，输出最终答案（只输出答案本身）。");
        return sb.toString();
    }

    private static String safeMessage(Throwable e) {
        return e == null || e.getMessage() == null ? "unknown error" : e.getMessage();
    }
}

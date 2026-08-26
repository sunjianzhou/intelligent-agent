package com.intelligent.agent.web.ai.agent.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.ai.agent.AgentRequestContext;
import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM 计划器（G6 planning 前置默认实现）：
 * <ol>
 *   <li>启发式判定复杂度（{@link PlanningComplexityDetector}），简单请求零开销；</li>
 *   <li>复杂请求用低温度 LLM 调用生成 JSON 计划，失败回退行解析；</li>
 *   <li>任何失败都返回 {@link Optional#empty()}，不阻塞正常执行。</li>
 * </ol>
 */
public class LlmTaskPlanner implements TaskPlanner {

    private static final Logger log = LoggerFactory.getLogger(LlmTaskPlanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SYSTEM_PROMPT =
            "你是一个任务规划器。用户提出了一个需要多步执行的任务。"
                    + "请把任务拆解为 2~6 个有序的执行步骤，每步聚焦单一动作。\n"
                    + "若多个步骤相互独立、可以并行研究，请给它们相同的 group 编号（正整数，从 1 开始）；"
                    + "有依赖或顺序要求的步骤使用不同的 group 编号或不填 group（默认串行）。\n"
                    + "只输出 JSON，不要输出任何其他文字：\n"
                    + "{\"steps\":[{\"title\":\"步骤标题\",\"detail\":\"执行要点（可选，一句话）\",\"group\":1}]}";

    private final LlmProviderRouter router;
    private final PlanningComplexityDetector detector;
    private final boolean enabled;
    private final Duration timeout;
    private final int maxSteps;

    public LlmTaskPlanner(LlmProviderRouter router) {
        this(router, new PlanningComplexityDetector(), true, Duration.ofSeconds(30), 6);
    }

    public LlmTaskPlanner(LlmProviderRouter router, PlanningComplexityDetector detector,
                          boolean enabled, Duration timeout, int maxSteps) {
        this.router = router;
        this.detector = detector == null ? new PlanningComplexityDetector() : detector;
        this.enabled = enabled;
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        this.maxSteps = Math.max(1, maxSteps);
    }

    @Override
    public Optional<ExecutionPlan> plan(AgentRequestContext context) {
        if (!enabled || context == null || !context.useTools()) {
            return Optional.empty();
        }
        if (!detector.isComplex(context.message())) {
            return Optional.empty();
        }
        try {
            ChatTurn turn = new ChatTurn(context.userId(), context.model(),
                    List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(context.message())),
                    Map.of("temperature", 0.2, "max_tokens", 1024));
            String answer = router.forUser(context.userId(), context.model())
                    .complete(turn).block(timeout);
            return parse(answer);
        } catch (Exception e) {
            log.warn("planning failed, continuing without plan: {}", safeMessage(e));
            return Optional.empty();
        }
    }

    /** JSON 解析优先，失败时按行拆解（- / 1. / 第一 等前缀剥除）。 */
    Optional<ExecutionPlan> parse(String answer) {
        if (answer == null || answer.isBlank()) {
            return Optional.empty();
        }
        String text = answer.trim();
        int fence = text.indexOf("```");
        if (fence != -1) {
            text = text.replaceAll("```(?:json)?", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start != -1 && end > start) {
            try {
                JsonNode root = MAPPER.readTree(text.substring(start, end + 1));
                JsonNode stepsNode = root.path("steps");
                if (stepsNode.isArray()) {
                    List<PlanStep> steps = new ArrayList<>();
                    for (JsonNode node : stepsNode) {
                        appendStep(node, steps, 0);
                    }
                    if (!steps.isEmpty()) {
                        return Optional.of(new ExecutionPlan(steps));
                    }
                }
            } catch (Exception e) {
                log.debug("plan JSON parse failed, falling back to line parsing: {}",
                        safeMessage(e));
            }
        }
        List<PlanStep> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (lines.size() >= maxSteps) {
                break;
            }
            String clean = line.replaceAll("^\\s*(?:[-*•]|\\d+[.、．)]|\\d+)\\s*", "").trim();
            if (clean.isBlank()) {
                continue;
            }
            lines.add(PlanStep.of(clean));
        }
        return lines.isEmpty() ? Optional.empty() : Optional.of(new ExecutionPlan(lines));
    }

    /**
     * 兼容本地模型的嵌套输出：步骤 title 本身是 {@code {"steps":[...]}} JSON 时
     * 递归展开为多个步骤（深度上限 3，受 maxSteps 总量约束）。
     */
    private void appendStep(JsonNode node, List<PlanStep> steps, int depth) {
        if (node == null || steps.size() >= maxSteps) {
            return;
        }
        String title = node.path("title").asText("").trim();
        JsonNode nested = depth < 3 ? tryParseSteps(title) : null;
        if (nested != null) {
            for (JsonNode inner : nested) {
                appendStep(inner, steps, depth + 1);
                if (steps.size() >= maxSteps) {
                    return;
                }
            }
            return;
        }
        if (title.isBlank()) {
            return;
        }
        int group = node.path("group").asInt(0);
        steps.add(new PlanStep(title, node.path("detail").asText(""), group));
    }

    /** title 若可解析为含 steps 数组的 JSON 对象则返回该数组，否则返回 null。 */
    private static JsonNode tryParseSteps(String title) {
        if (title == null || !title.startsWith("{") || !title.endsWith("}")) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(title);
            JsonNode steps = node.path("steps");
            return steps.isArray() ? steps : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeMessage(Throwable e) {
        return e == null || e.getMessage() == null ? "unknown error" : e.getMessage();
    }
}

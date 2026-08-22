package com.intelligent.agent.web.ai.skill;

import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.domain.skill.SkillService;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 技能运行时匹配与注入（迁移自 Python skills/manager.py + skills/applicator.py）：
 * <ul>
 *   <li>关键词命中 1 个 → 直接命中；多命中或零命中 → LLM 意图裁决（失败/无命中返回空，不阻断主流程）；</li>
 *   <li>命中后生成 [SKILL] 提示词注入聊天上下文，并按 forced_tools 过滤可用工具集；</li>
 *   <li>工具名匹配做归一化（去非字母数字、忽略大小写），兼容 "CalculatorTool" / "time_tool" 等旧名。</li>
 * </ul>
 */
@Slf4j
public class SkillMatcher {

    private final SkillService skillService;
    private final LlmProviderRouter router;
    private final Duration llmTimeout;
    private final boolean enabled;

    public SkillMatcher(SkillService skillService, LlmProviderRouter router,
                        boolean enabled, Duration llmTimeout) {
        this.skillService = skillService;
        this.router = router;
        this.enabled = enabled;
        this.llmTimeout = llmTimeout == null ? Duration.ofSeconds(10) : llmTimeout;
    }

    /** 返回命中的技能；关闭、无技能或 LLM 裁决失败时返回 empty。 */
    public Optional<Map<String, Object>> findSkill(String userId, String message) {
        if (!enabled || skillService == null || message == null || message.isBlank()) {
            return Optional.empty();
        }
        List<Map<String, Object>> enabledSkills = enabledSkills();
        if (enabledSkills.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> keywordMatches = matchByKeywords(message, enabledSkills);
        if (keywordMatches.size() == 1) {
            return Optional.of(keywordMatches.get(0));
        }
        if (keywordMatches.size() > 1) {
            return matchByLlm(userId, message, keywordMatches);
        }
        return matchByLlm(userId, message, enabledSkills);
    }

    /** 组装注入上下文的 [SKILL] 策略提示（格式与 Python build_injection_prompt 一致）。 */
    public static String buildInjectionPrompt(Map<String, Object> skill) {
        StringBuilder sb = new StringBuilder();
        String strategy = str(skill.get("overall_strategy"));
        if (!strategy.isBlank()) {
            sb.append("【整体目标】").append(strategy);
        }
        List<Map<String, Object>> steps = stepsOf(skill.get("steps"));
        if (!steps.isEmpty()) {
            sb.append("\n【执行步骤】请严格按以下顺序执行，每步完成后再进行下一步：");
            int i = 1;
            for (Map<String, Object> step : steps) {
                sb.append("\n第").append(i).append("步【").append(str(step.get("name"))).append("】");
                String desc = str(step.get("description"));
                if (!desc.isBlank()) {
                    sb.append("：").append(desc);
                }
                List<Object> forced = listOf(step.get("forced_tools"));
                List<Object> hints = listOf(step.get("tool_hints"));
                if (!forced.isEmpty()) {
                    sb.append("\n   → 必须调用工具：").append(String.join(", ", stringList(forced)));
                } else if (!hints.isEmpty()) {
                    sb.append("\n   → 建议使用工具：").append(String.join(", ", stringList(hints)));
                }
                String stepStrategy = str(step.get("strategy_prompt"));
                if (!stepStrategy.isBlank()) {
                    sb.append("\n   → 具体要求：").append(stepStrategy);
                }
                i++;
            }
            sb.append("\n完成所有步骤后，整合结果给用户一个清晰完整的回答。");
        }
        return sb.toString().trim();
    }

    /**
     * 按技能 forced_tools 过滤工具定义（skill 级 + 步骤级合并）。
     * 归一化匹配；过滤结果为空时回退全量，避免模型无工具可用。
     */
    public List<ToolDefinition> filterTools(List<ToolDefinition> all, Map<String, Object> skill) {
        List<String> forced = allForcedTools(skill);
        if (forced.isEmpty() || all == null || all.isEmpty()) {
            return all;
        }
        List<String> normalizedTokens = new ArrayList<>(forced.size());
        for (String token : forced) {
            normalizedTokens.add(normalize(token));
        }
        List<ToolDefinition> out = new ArrayList<>();
        for (ToolDefinition def : all) {
            String name = normalize(def.name());
            for (String token : normalizedTokens) {
                if (!token.isBlank() && (name.contains(token) || token.contains(name))) {
                    out.add(def);
                    break;
                }
            }
        }
        return out.isEmpty() ? all : out;
    }

    private List<Map<String, Object>> enabledSkills() {
        Object raw = skillService.listSkills(null, true).get("skills");
        if (!(raw instanceof List)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> skill = (Map<String, Object>) item;
                out.add(skill);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> matchByKeywords(String message,
                                                             List<Map<String, Object>> skills) {
        String msg = message.toLowerCase(Locale.ROOT);
        List<Map.Entry<Integer, Map<String, Object>>> scored = new ArrayList<>();
        for (Map<String, Object> skill : skills) {
            int hits = 0;
            for (Object kw : listOf(skill.get("trigger_keywords"))) {
                String keyword = String.valueOf(kw);
                if (!keyword.isBlank() && msg.contains(keyword.toLowerCase(Locale.ROOT))) {
                    hits++;
                }
            }
            if (hits >= 1) {
                scored.add(Map.entry(hits, skill));
            }
        }
        scored.sort((a, b) -> Integer.compare(b.getKey(), a.getKey()));
        List<Map<String, Object>> out = new ArrayList<>(scored.size());
        for (Map.Entry<Integer, Map<String, Object>> entry : scored) {
            out.add(entry.getValue());
        }
        return out;
    }

    private Optional<Map<String, Object>> matchByLlm(String userId, String message,
                                                     List<Map<String, Object>> candidates) {
        if (router == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder skillDesc = new StringBuilder();
        for (Map<String, Object> skill : candidates) {
            skillDesc.append("[").append(skill.get("id")).append("] ")
                    .append(str(skill.get("name"))).append(": ")
                    .append(str(skill.get("description"))).append('\n');
        }
        String prompt = "用户消息：「" + message + "」\n\n"
                + "可选技能：\n" + skillDesc
                + "\n请判断用户消息最匹配哪个技能ID，如果都不匹配请回答 none。"
                + "只回答技能ID或none，不要有其他内容。";
        try {
            String answer = router.forUser(userId, null)
                    .complete(new ChatTurn(userId == null ? "" : userId, null,
                            List.of(
                                    ChatMessage.system("你是意图分类助手，只输出技能ID或none。"),
                                    ChatMessage.user(prompt)),
                            Map.of("temperature", 0.0, "max_tokens", 32)))
                    .block(llmTimeout);
            if (answer == null) {
                return Optional.empty();
            }
            String result = answer.trim().toLowerCase(Locale.ROOT);
            if (result.isBlank() || result.contains("none")) {
                return Optional.empty();
            }
            for (Map<String, Object> skill : candidates) {
                String id = String.valueOf(skill.get("id"));
                if (id.equals(result) || result.contains(id)) {
                    return Optional.of(skill);
                }
            }
        } catch (Exception e) {
            log.warn("技能 LLM 意图裁决失败，跳过技能注入: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private static List<String> allForcedTools(Map<String, Object> skill) {
        Set<String> result = new LinkedHashSet<>();
        for (Object tool : listOf(skill.get("forced_tools"))) {
            result.add(String.valueOf(tool));
        }
        for (Map<String, Object> step : stepsOf(skill.get("steps"))) {
            for (Object tool : listOf(step.get("forced_tools"))) {
                result.add(String.valueOf(tool));
            }
        }
        return new ArrayList<>(result);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stepsOf(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof Map) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    private static List<Object> listOf(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    private static List<String> stringList(List<Object> values) {
        List<String> out = new ArrayList<>(values.size());
        for (Object v : values) {
            out.add(String.valueOf(v));
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}

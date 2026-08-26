package com.intelligent.agent.web.ai.agent.planning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 生成的执行计划（有序步骤列表）。
 *
 * @param steps 有序步骤
 */
public record ExecutionPlan(List<PlanStep> steps) {

    public ExecutionPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static ExecutionPlan of(PlanStep... steps) {
        return new ExecutionPlan(List.of(steps));
    }

    /**
     * R-07：按并行分组展开步骤——group&lt;=0 的步骤各自串行（保持原顺序），
     * 相同正整数 group 的步骤归入同一并行组；组按首次出现顺序返回。
     */
    public List<List<PlanStep>> parallelGroups() {
        List<List<PlanStep>> out = new ArrayList<>();
        Map<String, List<PlanStep>> byKey = new LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            PlanStep step = steps.get(i);
            String key = step.group() <= 0 ? "serial" + i : "group" + step.group();
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(step);
        }
        for (List<PlanStep> group : byKey.values()) {
            out.add(List.copyOf(group));
        }
        return out;
    }
}

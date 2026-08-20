package com.intelligent.agent.web.ai.agent.planning;

import java.util.List;

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
}

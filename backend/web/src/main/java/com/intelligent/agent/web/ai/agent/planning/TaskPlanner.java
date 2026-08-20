package com.intelligent.agent.web.ai.agent.planning;

import com.intelligent.agent.web.ai.agent.AgentRequestContext;

import java.util.Optional;

/**
 * 任务规划器：判定请求是否需要计划，并生成有序执行计划。
 * <p>
 * 实现必须优雅降级——模型失败/超时/解析失败时返回 {@link Optional#empty()}，
 * 调用方直接按"无计划"继续正常执行。
 */
public interface TaskPlanner {

    Optional<ExecutionPlan> plan(AgentRequestContext context);
}

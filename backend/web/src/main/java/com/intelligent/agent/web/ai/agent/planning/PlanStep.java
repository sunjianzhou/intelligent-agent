package com.intelligent.agent.web.ai.agent.planning;

/**
 * 执行计划中的一步。
 *
 * @param title  步骤标题（必填）
 * @param detail 执行要点（可选，可为空串）
 */
public record PlanStep(String title, String detail) {

    public PlanStep {
        title = title == null ? "" : title.trim();
        detail = detail == null ? "" : detail.trim();
    }

    public static PlanStep of(String title) {
        return new PlanStep(title, "");
    }
}

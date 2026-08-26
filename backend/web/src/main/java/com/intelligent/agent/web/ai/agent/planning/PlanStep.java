package com.intelligent.agent.web.ai.agent.planning;

/**
 * 执行计划中的一步。
 *
 * @param title  步骤标题（必填）
 * @param detail 执行要点（可选，可为空串）
 * @param group  R-07 并行分组：相同正整数归入同一并行组（组间按序）；&lt;=0 表示串行（默认）
 */
public record PlanStep(String title, String detail, int group) {

    public PlanStep {
        title = title == null ? "" : title.trim();
        detail = detail == null ? "" : detail.trim();
        group = Math.max(0, group);
    }

    public PlanStep(String title) {
        this(title, "", 0);
    }

    public PlanStep(String title, String detail) {
        this(title, detail, 0);
    }

    public static PlanStep of(String title) {
        return new PlanStep(title, "");
    }
}

package com.intelligent.agent.web.ai.agent.subagent;

/**
 * 单个子代理的执行结果（R-07）。
 *
 * @param stepIndex  在原计划步骤列表中的下标（0-based，供按序合并）
 * @param title      步骤标题
 * @param detail     步骤要点（可为空）
 * @param status     ok / error（超时归入 error）
 * @param text       子代理产出文本（已按配置截断）
 * @param error      失败原因（成功时为空）
 * @param durationMs 耗时（毫秒）
 */
public record SubAgentResult(
        int stepIndex,
        String title,
        String detail,
        String status,
        String text,
        String error,
        long durationMs) {

    public SubAgentResult {
        title = title == null ? "" : title.trim();
        detail = detail == null ? "" : detail.trim();
        status = status == null ? "error" : status;
        text = text == null ? "" : text;
        error = error == null ? "" : error;
        stepIndex = Math.max(0, stepIndex);
    }

    public static SubAgentResult ok(int stepIndex, String title, String detail,
                                    String text, long durationMs) {
        return new SubAgentResult(stepIndex, title, detail, "ok", text, "", durationMs);
    }

    public static SubAgentResult error(int stepIndex, String title, String detail,
                                       String error, long durationMs) {
        return new SubAgentResult(stepIndex, title, detail, "error", "", error, durationMs);
    }
}

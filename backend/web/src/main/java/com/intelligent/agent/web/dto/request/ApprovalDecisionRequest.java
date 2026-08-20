package com.intelligent.agent.web.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * HITL 审批决议请求体：{@code {"approved": true/false}}。
 *
 * @param approved true=批准执行；false=拒绝
 */
public record ApprovalDecisionRequest(@NotNull Boolean approved) {
}

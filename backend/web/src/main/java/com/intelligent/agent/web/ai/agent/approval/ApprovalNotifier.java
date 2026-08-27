package com.intelligent.agent.web.ai.agent.approval;

/**
 * R-09：IM 渠道 HITL 审批送达抽象。
 * <p>
 * web/WS 渠道由编排器直接发 {@code approval_required} 事件到前端；IM 渠道没有该事件通道，
 * 由各渠道适配器把审批请求推送给用户（飞书卡片按钮），无按钮渠道默认拒绝并提示在 Web 端批准。
 */
public interface ApprovalNotifier {

    /** 该渠道是否支持内联审批交互（卡片按钮等）。 */
    boolean supports(String channel);

    /**
     * 把审批请求推送给用户。
     *
     * @return true = 已送达并可等待决议；false = 送达失败（调用方按拒绝处理）
     */
    boolean requestApproval(String channel, String replyAddress,
                            ApprovalGate.ApprovalRequest request);

    /** 无审批 UI 渠道的默认拒绝提示（replyAddress 为空时静默）。 */
    void notifyDenied(String channel, String replyAddress, String toolName);
}

package com.intelligent.agent.web.im;

import com.intelligent.agent.web.ai.agent.approval.ApprovalGate;
import com.intelligent.agent.web.ai.agent.approval.ApprovalNotifier;
import com.intelligent.agent.web.feishu.FeishuCardBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * R-09：飞书渠道 HITL 审批送达——用卡片按钮承载批准/拒绝，
 * 决议由 FeishuEventController 卡片回调按 value.key 解析后调用 ApprovalGate.resolve。
 */
@Slf4j
@Component
public class FeishuApprovalNotifier implements ApprovalNotifier {

    private static final String FEISHU_CHANNEL = "feishu_im";

    private final FeishuChannelAdapter adapter;

    public FeishuApprovalNotifier(FeishuChannelAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public boolean supports(String channel) {
        return FEISHU_CHANNEL.equals(channel);
    }

    @Override
    public boolean requestApproval(String channel, String replyAddress,
                                   ApprovalGate.ApprovalRequest request) {
        if (replyAddress == null || replyAddress.isBlank()) {
            log.warn("飞书审批卡片缺少回执地址，approvalId={}", request.approvalId());
            return false;
        }
        try {
            SendResult result = adapter.sendCard(replyAddress,
                    FeishuCardBuilder.approvalCard(request.approvalId(), request.eventData()),
                    "p2p");
            if (result.isSuccess()) {
                log.info("飞书审批卡片已送达 approvalId={}, receiver={}",
                        request.approvalId(), replyAddress);
                return true;
            }
            log.warn("飞书审批卡片发送失败 approvalId={}: {}",
                    request.approvalId(), result.getError());
            return false;
        } catch (Exception e) {
            log.error("飞书审批卡片发送异常 approvalId={}: {}",
                    request.approvalId(), e.getMessage());
            return false;
        }
    }

    @Override
    public void notifyDenied(String channel, String replyAddress, String toolName) {
        if (replyAddress == null || replyAddress.isBlank()) {
            return;
        }
        try {
            adapter.sendText(replyAddress,
                    "⚠️ 操作「" + toolName + "」需要人工审批，请在 Web 端批准后重试。", "p2p");
        } catch (Exception e) {
            log.warn("飞书审批拒绝提示发送失败: {}", e.getMessage());
        }
    }
}

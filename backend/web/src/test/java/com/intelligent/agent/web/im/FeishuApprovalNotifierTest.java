package com.intelligent.agent.web.im;

import com.intelligent.agent.web.ai.agent.approval.ApprovalGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R-09：飞书审批卡片送达（成功 / 失败降级拒绝 / 拒绝提示）。
 */
class FeishuApprovalNotifierTest {

    @Mock FeishuChannelAdapter adapter;

    private FeishuApprovalNotifier notifier;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        notifier = new FeishuApprovalNotifier(adapter);
    }

    @Test
    void supportsOnlyFeishuChannel() {
        assertThat(notifier.supports("feishu_im")).isTrue();
        assertThat(notifier.supports("wecom")).isFalse();
        assertThat(notifier.supports("web")).isFalse();
    }

    @Test
    void requestApproval_sendsCardAndReturnsTrue() {
        when(adapter.sendCard(eq("oc_chat1"), any(), any()))
                .thenReturn(new SendResult(true, "om_1", null, ChannelType.FEISHU, 0));
        ApprovalGate gate = new ApprovalGate(true, Duration.ofSeconds(10));
        ApprovalGate.ApprovalRequest request = gate.request("u1", "channel_message",
                Map.of("message", "hi"));

        boolean delivered = notifier.requestApproval("feishu_im", "oc_chat1", request);

        assertThat(delivered).isTrue();
        verify(adapter).sendCard(eq("oc_chat1"), any(), any());
    }

    @Test
    void requestApproval_sendFailureReturnsFalse() {
        when(adapter.sendCard(any(), any(), any()))
                .thenReturn(new SendResult(false, null, "boom", ChannelType.FEISHU, 0));
        ApprovalGate gate = new ApprovalGate(true, Duration.ofSeconds(10));
        ApprovalGate.ApprovalRequest request = gate.request("u1", "channel_message", Map.of());

        assertThat(notifier.requestApproval("feishu_im", "oc_chat1", request)).isFalse();
    }

    @Test
    void requestApproval_missingReplyAddressReturnsFalse() {
        ApprovalGate gate = new ApprovalGate(true, Duration.ofSeconds(10));
        ApprovalGate.ApprovalRequest request = gate.request("u1", "channel_message", Map.of());

        assertThat(notifier.requestApproval("feishu_im", "  ", request)).isFalse();
        verify(adapter, never()).sendCard(any(), any(), any());
    }

    @Test
    void notifyDenied_sendsWebHint() {
        notifier.notifyDenied("wecom", "wx123", "channel_message");
        verify(adapter).sendText(eq("wx123"), org.mockito.ArgumentMatchers.contains("Web 端批准"),
                any());
    }

    @Test
    void notifyDenied_missingReplyAddress_isNoop() {
        notifier.notifyDenied("wecom", null, "channel_message");
        verify(adapter, never()).sendText(any(), any(), any());
    }
}

package com.intelligent.agent.web.ai.agent.approval;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审批门测试：请求/决议/超时拒绝/用户隔离/禁用时直通。
 */
class ApprovalGateTest {

    @Test
    void approvedDecisionLetsAwaitReturnTrue() {
        ApprovalGate gate = new ApprovalGate(true, Duration.ofSeconds(5));
        ApprovalGate.ApprovalRequest request =
                gate.request("u1", "channel_message", Map.of("message", "hi"));

        assertThat(gate.resolve(request.approvalId(), "u1", true)).isTrue();
        assertThat(gate.await(request)).isTrue();
        assertThat(gate.registry().pendingCount()).isZero();
    }

    @Test
    void deniedDecisionLetsAwaitReturnFalse() {
        ApprovalGate gate = new ApprovalGate(true, Duration.ofSeconds(5));
        ApprovalGate.ApprovalRequest request =
                gate.request("u1", "channel_message", Map.of("message", "hi"));

        assertThat(gate.resolve(request.approvalId(), "u1", false)).isTrue();
        assertThat(gate.await(request)).isFalse();
    }

    @Test
    void timeoutReturnsFalseAndCleansUp() {
        ApprovalGate gate = new ApprovalGate(true, Duration.ofMillis(150));
        ApprovalGate.ApprovalRequest request =
                gate.request("u1", "channel_message", Map.of("message", "hi"));

        assertThat(gate.await(request)).isFalse();
        assertThat(gate.registry().pendingCount()).isZero();
    }

    @Test
    void resolveRejectsOtherUsers() {
        ApprovalGate gate = new ApprovalGate(true, Duration.ofSeconds(5));
        ApprovalGate.ApprovalRequest request =
                gate.request("u1", "channel_message", Map.of("message", "hi"));

        assertThat(gate.resolve(request.approvalId(), "u2", true)).isFalse();
        assertThat(gate.resolve("aprv_missing", "u1", true)).isFalse();
    }

    @Test
    void disabledGateAwaitReturnsTrueImmediately() {
        ApprovalGate gate = new ApprovalGate(false, Duration.ofSeconds(5));
        ApprovalGate.ApprovalRequest request =
                gate.request("u1", "channel_message", Map.of("message", "hi"));

        assertThat(gate.await(request)).isTrue();
        assertThat(gate.registry().pendingCount()).isZero();
    }

    @Test
    void requestEventDataCarriesToolAndArgs() {
        ApprovalGate gate = new ApprovalGate(true, Duration.ofSeconds(5));
        ApprovalGate.ApprovalRequest request =
                gate.request("u1", "channel_message", Map.of("message", "hi"));

        assertThat(request.eventData().get("tool")).isEqualTo("channel_message");
        assertThat(request.eventData().get("approval_id")).isEqualTo(request.approvalId());
        assertThat(request.eventData().get("args")).isEqualTo(Map.of("message", "hi"));
    }
}

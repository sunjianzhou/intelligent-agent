package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.ai.agent.approval.ApprovalGate;
import com.intelligent.agent.web.dto.request.ApprovalDecisionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审批决议 REST 端点测试：正确用户可决议、未知 id/用户不匹配 404。
 */
class ApprovalControllerTest {

    private final ApprovalGate gate = new ApprovalGate(true, Duration.ofSeconds(5));
    private final ApprovalController controller = new ApprovalController(gate);

    @Test
    void ownerCanResolveApproval() {
        ApprovalGate.ApprovalRequest request =
                gate.request("u1", "channel_message", Map.of("message", "hi"));

        ResponseEntity<?> response = controller.decide(
                request.approvalId(), new ApprovalDecisionRequest(true), "u1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 模拟被阻塞的编排线程收到决议后继续并清理
        assertThat(gate.await(request)).isTrue();
        assertThat(gate.registry().pendingCount()).isZero();
    }

    @Test
    void unknownApprovalReturns404() {
        ResponseEntity<?> response = controller.decide(
                "aprv_missing", new ApprovalDecisionRequest(true), "u1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void otherUserCannotResolve() {
        ApprovalGate.ApprovalRequest request =
                gate.request("u1", "channel_message", Map.of("message", "hi"));

        ResponseEntity<?> response = controller.decide(
                request.approvalId(), new ApprovalDecisionRequest(true), "u2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}

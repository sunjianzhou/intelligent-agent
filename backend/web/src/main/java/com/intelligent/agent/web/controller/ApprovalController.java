package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.ai.agent.approval.ApprovalGate;
import com.intelligent.agent.web.dto.request.ApprovalDecisionRequest;
import com.intelligent.agent.web.dto.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HITL 审批决议端点：SSE/CLI 等其他客户端可通过 REST 响应审批请求。
 * 真实用户 ID 一律取 JWT（与 ChatController 一致），不信任请求体。
 */
@Slf4j
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalGate approvalGate;

    public ApprovalController(ApprovalGate approvalGate) {
        this.approvalGate = approvalGate;
    }

    @PostMapping("/{approvalId}")
    public ResponseEntity<?> decide(@PathVariable String approvalId,
                                    @RequestBody ApprovalDecisionRequest request,
                                    HttpServletRequest httpRequest) {
        return decide(approvalId, request, UserContext.userId(httpRequest));
    }

    /** 包内可见的决议实现（userId 由调用方注入，便于单元测试）。 */
    ResponseEntity<?> decide(String approvalId, ApprovalDecisionRequest request, String userId) {
        boolean resolved = approvalGate.resolve(approvalId, userId,
                Boolean.TRUE.equals(request.approved()));
        if (!resolved) {
            return ResponseEntity.notFound().build();
        }
        log.info("审批决议: approvalId={}, userId={}, approved={}",
                approvalId, userId, request.approved());
        return ResponseEntity.ok(ApiResponse.success("审批已受理：" + approvalId));
    }
}

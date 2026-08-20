package com.intelligent.agent.web.ai.agent.approval;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审批注册表（HITL 审批门）：approval_id → 待审批记录。
 * 决议按 userId 隔离；过期/超时清理时自动以「拒绝」完成 future。
 */
public class ApprovalRegistry {

    private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();

    public record PendingApproval(String approvalId, String userId, String toolName,
                                  Map<String, Object> arguments,
                                  CompletableFuture<Boolean> future) {
    }

    public String register(String userId, String toolName, Map<String, Object> args) {
        String id = "aprv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        pending.put(id, new PendingApproval(id, userId == null ? "" : userId, toolName,
                args == null ? Map.of() : Map.copyOf(args), new CompletableFuture<>()));
        return id;
    }

    public CompletableFuture<Boolean> await(String approvalId) {
        PendingApproval approval = pending.get(approvalId);
        return approval == null
                ? CompletableFuture.completedFuture(false)
                : approval.future();
    }

    /** 只有归属用户能决议；返回 false 表示 id 不存在或用户不匹配。 */
    public boolean resolve(String approvalId, String userId, boolean approved) {
        PendingApproval approval = pending.get(approvalId);
        if (approval == null) {
            return false;
        }
        if (!approval.userId().equals(userId == null ? "" : userId)) {
            return false;
        }
        return approval.future().complete(approved);
    }

    /** 移除记录并完成 future（未决议时按拒绝处理，防止悬挂）。 */
    public void expire(String approvalId) {
        PendingApproval approval = pending.remove(approvalId);
        if (approval != null) {
            approval.future().complete(false);
        }
    }

    public int pendingCount() {
        return pending.size();
    }
}

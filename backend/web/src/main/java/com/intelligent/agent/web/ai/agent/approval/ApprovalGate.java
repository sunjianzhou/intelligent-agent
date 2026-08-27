package com.intelligent.agent.web.ai.agent.approval;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HITL 审批门（G6）：不可逆工具调用在 web/WS 渠道先请求用户审批。
 * <p>
 * 编排器流程：{@link #request} 注册并拿到 approval_id → 发出
 * {@code approval_required} 事件 → {@link #await} 阻塞等待决议；
 * 批准返回 true，拒绝/超时返回 false（默认安全拒绝）。禁用时直通 true。
 */
public class ApprovalGate {

    private final ApprovalRegistry registry;
    private final boolean enabled;
    private final Duration timeout;

    public ApprovalGate(boolean enabled, Duration timeout) {
        this(new ApprovalRegistry(), enabled, timeout);
    }

    public ApprovalGate(ApprovalRegistry registry, boolean enabled, Duration timeout) {
        this.registry = registry == null ? new ApprovalRegistry() : registry;
        this.enabled = enabled;
        this.timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
    }

    public boolean enabled() {
        return enabled;
    }

    public ApprovalRegistry registry() {
        return registry;
    }

    /** 注册一次审批请求，返回 approval_id + 事件数据。 */
    public ApprovalRequest request(String userId, String toolName, Map<String, Object> args) {
        String approvalId = registry.register(userId, toolName, args);
        return new ApprovalRequest(approvalId, Map.of(
                "approval_id", approvalId,
                "tool", toolName,
                "args", args == null ? Map.of() : args));
    }

    /** 阻塞等待决议；批准 true，拒绝/超时/异常 false；禁用时直通 true。 */
    public boolean await(ApprovalRequest request) {
        if (!enabled) {
            registry.expire(request.approvalId());
            return true;
        }
        try {
            return registry.await(request.approvalId())
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        } finally {
            registry.expire(request.approvalId());
        }
    }

    /** 外部决议入口（WS 消息 / REST 端点）；非归属用户返回 false。 */
    public boolean resolve(String approvalId, String userId, boolean approved) {
        return registry.resolve(approvalId, userId, approved);
    }

    /** R-09：无审批 UI 渠道/送达失败时按拒绝直接完结（不阻塞等待）。 */
    public void deny(ApprovalRequest request) {
        registry.expire(request.approvalId());
    }

    public record ApprovalRequest(String approvalId, Map<String, Object> eventData) {
    }
}

package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.agent.approval.ApprovalGate;
import com.intelligent.agent.web.ai.agent.approval.ApprovalNotifier;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.LlmResponse;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolCall;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G6 HITL 审批门编排集成测试：审批事件、批准执行、拒绝/超时取消、IM 渠道直发。
 */
class AgentOrchestratorHitlTest {

    /** 需审批的工具：记录实际执行次数。 */
    static class GatedChannelTool implements AgentTool {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("channel_message", "向 IM 渠道发送消息", false, null,
                    Duration.ofSeconds(5)).requireApproval();
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            calls.incrementAndGet();
            return "sent:" + arguments.get("message");
        }
    }

    static class ScriptedProvider implements LlmProvider {
        final List<Mono<LlmResponse>> toolRounds;
        final List<ChatTurn> turns = new ArrayList<>();
        final int[] toolCalls = {0};

        ScriptedProvider(List<Mono<LlmResponse>> toolRounds) {
            this.toolRounds = toolRounds;
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            return Flux.empty();
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            return Mono.just("");
        }

        @Override
        public Mono<LlmResponse> completeWithTools(ChatTurn turn,
                                                   List<ToolDefinition> tools) {
            turns.add(turn);
            int idx = Math.min(toolCalls[0]++, toolRounds.size() - 1);
            return toolRounds.get(idx);
        }
    }

    private static final ToolCall GATED_CALL =
            ToolCall.of("channel_message", Map.of("message", "大家好"));
    private static final String REQUEST = "请发消息给大家";

    private AgentOrchestrator orchestrator(ScriptedProvider provider,
                                           GatedChannelTool tool,
                                           ApprovalGate gate) {
        return new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                new ToolExecutor(List.of(tool)),
                null, null, null,
                AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS, null, null,
                null, null, gate);
    }

    private AgentRequestContext ctx(String channel) {
        return new AgentRequestContext("u1", REQUEST, null, null, null, null,
                true, true, channel, Map.of());
    }

    @Test
    void streamRequestsApprovalAndExecutesAfterApproval() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                Mono.just(new LlmResponse("", List.of(GATED_CALL))),
                Mono.just(new LlmResponse("已发送", List.of()))));
        GatedChannelTool tool = new GatedChannelTool();
        ApprovalGate gate = new ApprovalGate(true, Duration.ofSeconds(10));
        AtomicReference<String> approvalId = new AtomicReference<>();

        StepVerifier.create(orchestrator(provider, tool, gate).stream(ctx(null)))
                .expectNextMatches(e -> {
                    if (!e.type().equals("approval_required")) {
                        return false;
                    }
                    Map<?, ?> data = (Map<?, ?>) e.data();
                    approvalId.set(String.valueOf(data.get("approval_id")));
                    return "channel_message".equals(data.get("tool"));
                })
                .then(() -> assertThat(gate.resolve(approvalId.get(), "u1", true)).isTrue())
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals("已发送"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();

        assertThat(tool.calls.get()).isEqualTo(1);
    }

    @Test
    void deniedApprovalSkipsToolAndInformsModel() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                Mono.just(new LlmResponse("", List.of(GATED_CALL))),
                Mono.just(new LlmResponse("好的，已取消", List.of()))));
        GatedChannelTool tool = new GatedChannelTool();
        ApprovalGate gate = new ApprovalGate(true, Duration.ofSeconds(10));
        AtomicReference<String> approvalId = new AtomicReference<>();

        StepVerifier.create(orchestrator(provider, tool, gate).stream(ctx(null)))
                .expectNextMatches(e -> {
                    if (!e.type().equals("approval_required")) {
                        return false;
                    }
                    approvalId.set(String.valueOf(((Map<?, ?>) e.data()).get("approval_id")));
                    return true;
                })
                .then(() -> assertThat(gate.resolve(approvalId.get(), "u1", false)).isTrue())
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals("好的，已取消"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();

        assertThat(tool.calls.get()).isZero();
        // 第二轮提示词里能看到拒绝说明
        String round2 = provider.turns.get(1).messages().stream()
                .filter(m -> "tool".equals(m.role()))
                .map(m -> m.content())
                .reduce("", (a, b) -> a + b);
        assertThat(round2).contains("用户拒绝");
    }

    @Test
    void approvalTimeoutSkipsTool() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                Mono.just(new LlmResponse("", List.of(GATED_CALL))),
                Mono.just(new LlmResponse("审批超时，已取消", List.of()))));
        GatedChannelTool tool = new GatedChannelTool();
        ApprovalGate gate = new ApprovalGate(true, Duration.ofMillis(200));

        StepVerifier.create(orchestrator(provider, tool, gate).stream(ctx(null)))
                .expectNextMatches(e -> e.type().equals("approval_required"))
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .expectNextMatches(e -> e.type().equals("token"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();

        assertThat(tool.calls.get()).isZero();
    }

    @Test
    void imChannelWithoutNotifier_deniesGatedTool() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                Mono.just(new LlmResponse("", List.of(GATED_CALL))),
                Mono.just(new LlmResponse("需要审批，已取消", List.of()))));
        GatedChannelTool tool = new GatedChannelTool();
        ApprovalGate gate = new ApprovalGate(true, Duration.ofSeconds(10));

        StepVerifier.create(orchestrator(provider, tool, gate).stream(ctx("feishu_im")))
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .expectNextMatches(e -> e.type().equals("token")
                        && e.data().equals("需要审批，已取消"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();

        // 无审批 UI 的 IM 渠道默认拒绝，工具不执行
        assertThat(tool.calls.get()).isZero();
    }

    @Test
    void feishuChannelWithNotifier_sendsCardAndExecutesAfterApproval() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                Mono.just(new LlmResponse("", List.of(GATED_CALL))),
                Mono.just(new LlmResponse("已发送", List.of()))));
        GatedChannelTool tool = new GatedChannelTool();
        ApprovalGate gate = new ApprovalGate(true, Duration.ofSeconds(10));
        // 送达即模拟用户点击「批准」（在阻塞等待前完成决议）
        FakeNotifier notifier = new FakeNotifier(true,
                req -> gate.resolve(req.approvalId(), "u1", true));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                new ToolExecutor(List.of(tool)),
                null, null, null,
                AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS, null, null,
                null, null, gate, null, null, null, notifier);

        StepVerifier.create(orchestrator.stream(
                        new AgentRequestContext("u1", REQUEST, null, null, null, null,
                                true, true, "feishu_im", Map.of(), null, null, false,
                                List.of(), null, "oc_chat1")))
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals("已发送"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();

        assertThat(tool.calls.get()).isEqualTo(1);
        assertThat(notifier.lastReplyAddress).isEqualTo("oc_chat1");
        assertThat(notifier.lastApprovalId).isNotNull();
    }

    @Test
    void feishuChannelDeliveryFailure_deniesToolAndNotifies() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                Mono.just(new LlmResponse("", List.of(GATED_CALL))),
                Mono.just(new LlmResponse("审批卡片发送失败，已取消", List.of()))));
        GatedChannelTool tool = new GatedChannelTool();
        ApprovalGate gate = new ApprovalGate(true, Duration.ofSeconds(10));
        FakeNotifier notifier = new FakeNotifier(false);

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                new ToolExecutor(List.of(tool)),
                null, null, null,
                AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS, null, null,
                null, null, gate, null, null, null, notifier);

        StepVerifier.create(orchestrator.stream(ctx("feishu_im")))
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .expectNextMatches(e -> e.type().equals("token"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();

        assertThat(tool.calls.get()).isZero();
        assertThat(notifier.deniedTool).isEqualTo("channel_message");
    }

    @Test
    void nonGatedToolNeedsNoApproval() {
        AgentTool echo = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("echo", "回显", true, null, null);
            }

            @Override
            public Object execute(Map<String, Object> arguments) {
                return "echo:" + arguments.get("text");
            }
        };
        ScriptedProvider provider = new ScriptedProvider(List.of(
                Mono.just(new LlmResponse("", List.of(ToolCall.of("echo", Map.of("text", "hi"))))),
                Mono.just(new LlmResponse("回声完毕", List.of()))));
        ApprovalGate gate = new ApprovalGate(true, Duration.ofSeconds(10));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                new ToolExecutor(List.of(echo)),
                null, null, null,
                AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS, null, null,
                null, null, gate);

        StepVerifier.create(orchestrator.stream(ctx(null)))
                .expectNextMatches(e -> e.type().equals("tool_calls_done"))
                .expectNextMatches(e -> e.type().equals("token") && e.data().equals("回声完毕"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();
    }

    @Test
    void completeTimesOutAndSkipsTool() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                Mono.just(new LlmResponse("", List.of(GATED_CALL))),
                Mono.just(new LlmResponse("超时取消", List.of()))));
        GatedChannelTool tool = new GatedChannelTool();
        ApprovalGate gate = new ApprovalGate(true, Duration.ofMillis(200));

        String answer = orchestrator(provider, tool, gate).complete(ctx(null))
                .block(Duration.ofSeconds(5));

        assertThat(answer).isEqualTo("超时取消");
        assertThat(tool.calls.get()).isZero();
    }

    /** R-09 测试用假 notifier：记录送达/拒绝行为。 */
    static class FakeNotifier implements ApprovalNotifier {
        final boolean delivered;
        final java.util.function.Consumer<ApprovalGate.ApprovalRequest> onRequest;
        volatile String lastApprovalId;
        volatile String lastReplyAddress;
        volatile String deniedTool;

        FakeNotifier(boolean delivered) {
            this(delivered, null);
        }

        FakeNotifier(boolean delivered,
                     java.util.function.Consumer<ApprovalGate.ApprovalRequest> onRequest) {
            this.delivered = delivered;
            this.onRequest = onRequest;
        }

        @Override
        public boolean supports(String channel) {
            return "feishu_im".equals(channel);
        }

        @Override
        public boolean requestApproval(String channel, String replyAddress,
                                       ApprovalGate.ApprovalRequest request) {
            this.lastApprovalId = request.approvalId();
            this.lastReplyAddress = replyAddress;
            if (onRequest != null) {
                onRequest.accept(request);
            }
            return delivered;
        }

        @Override
        public void notifyDenied(String channel, String replyAddress, String toolName) {
            this.deniedTool = toolName;
        }
    }
}

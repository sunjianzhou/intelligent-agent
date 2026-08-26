package com.intelligent.agent.web.ai.agent.subagent;

import com.intelligent.agent.web.ai.agent.AgentRequestContext;
import com.intelligent.agent.web.ai.agent.planning.ExecutionPlan;
import com.intelligent.agent.web.ai.agent.planning.PlanStep;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-07 子代理执行器测试：并行派发 + 按序合并、串行组语义、只读工具强制、失败隔离。
 */
class SubAgentExecutorTest {

    /** 按任务标记（最后一条 user 消息）脚本化回复，支持并发与故障注入。 */
    static class MarkerProvider implements LlmProvider {
        final Map<String, List<String>> scriptedReplies;
        final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
        final CyclicBarrier barrier;
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger concurrentMax = new AtomicInteger();
        final List<ChatTurn> turns = Collections.synchronizedList(new ArrayList<>());

        MarkerProvider(Map<String, List<String>> scriptedReplies, CyclicBarrier barrier) {
            this.scriptedReplies = scriptedReplies;
            this.barrier = barrier;
        }

        @Override
        public String name() {
            return "marker";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            return Flux.empty();
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            turns.add(turn);
            int now = active.incrementAndGet();
            concurrentMax.accumulateAndGet(now, Math::max);
            try {
                if (barrier != null) {
                    barrier.await(3, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                return Mono.error(new RuntimeException("parallel barrier failed: " + e));
            } finally {
                active.decrementAndGet();
            }
            String reply = replyFor(turn);
            return reply.startsWith("ERR:")
                    ? Mono.error(new RuntimeException(reply.substring(4)))
                    : Mono.just(reply);
        }

        private String replyFor(ChatTurn turn) {
            String user = lastUserMessage(turn);
            for (Map.Entry<String, List<String>> entry : scriptedReplies.entrySet()) {
                if (user.contains(entry.getKey())) {
                    List<String> chain = entry.getValue();
                    int idx = counters.computeIfAbsent(entry.getKey(),
                            k -> new AtomicInteger()).getAndIncrement();
                    return chain.get(Math.min(idx, chain.size() - 1));
                }
            }
            return "done";
        }

        static String lastUserMessage(ChatTurn turn) {
            for (int i = turn.messages().size() - 1; i >= 0; i--) {
                if ("user".equals(turn.messages().get(i).role())) {
                    return turn.messages().get(i).content();
                }
            }
            return "";
        }
    }

    static class FakeTool implements AgentTool {
        final String name;
        final boolean readOnly;
        final AtomicInteger calls = new AtomicInteger();

        FakeTool(String name, boolean readOnly) {
            this.name = name;
            this.readOnly = readOnly;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name, "fake " + name, readOnly, null, null);
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            calls.incrementAndGet();
            return name + ":" + arguments;
        }
    }

    private SubAgentExecutor executor(MarkerProvider provider, ToolExecutor toolExecutor,
                                      boolean enabled, int poolSize, List<String> allowedTools) {
        return new SubAgentExecutor(new LlmProviderRouter(provider, null, List.of()),
                toolExecutor, null, null, null, enabled, poolSize, 32,
                Duration.ofSeconds(5), 3, 500, allowedTools);
    }

    private AgentRequestContext ctx() {
        return AgentRequestContext.of("u1", "主任务");
    }

    @Test
    void executesSameGroupStepsInParallelAndMergesInStepOrder() {
        Map<String, List<String>> replies = new LinkedHashMap<>();
        replies.put("步骤A", List.of("A 完成"));
        replies.put("步骤B", List.of("B 完成"));
        MarkerProvider provider = new MarkerProvider(replies, new CyclicBarrier(2));

        List<SubAgentResult> results = executor(provider, new ToolExecutor(List.of()),
                true, 2, List.of())
                .execute(ctx(), ExecutionPlan.of(
                        new PlanStep("步骤A", "", 1),
                        new PlanStep("步骤B", "", 1)), null);

        assertThat(provider.concurrentMax.get()).isEqualTo(2);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).stepIndex()).isZero();
        assertThat(results.get(0).status()).isEqualTo("ok");
        assertThat(results.get(0).text()).isEqualTo("A 完成");
        assertThat(results.get(1).stepIndex()).isEqualTo(1);
        assertThat(results.get(1).text()).isEqualTo("B 完成");
    }

    @Test
    void ungroupedStepsRunSerially() {
        Map<String, List<String>> replies = new LinkedHashMap<>();
        replies.put("步骤A", List.of("A 完成"));
        replies.put("步骤B", List.of("B 完成"));
        MarkerProvider provider = new MarkerProvider(replies, null);

        List<SubAgentResult> results = executor(provider, new ToolExecutor(List.of()),
                true, 2, List.of())
                .execute(ctx(), ExecutionPlan.of(
                        new PlanStep("步骤A", "", 0),
                        new PlanStep("步骤B", "", 0)), null);

        assertThat(provider.concurrentMax.get()).isEqualTo(1);
        assertThat(results).extracting(SubAgentResult::status)
                .containsExactly("ok", "ok");
    }

    @Test
    void rejectsSideEffectToolsNotInAllowlist() {
        FakeTool writeTool = new FakeTool("write_file", false);
        FakeTool echoTool = new FakeTool("echo", true);
        ToolExecutor tools = new ToolExecutor(List.of(writeTool, echoTool));

        Map<String, List<String>> replies = new LinkedHashMap<>();
        replies.put("调研", List.of(
                "<tool_call>{\"tool\": \"write_file\", \"args\": {\"path\": \"x\"}}</tool_call>",
                "调研结论"));
        MarkerProvider provider = new MarkerProvider(replies, null);

        List<SubAgentResult> results = executor(provider, tools, true, 1,
                List.of("echo")).execute(ctx(),
                ExecutionPlan.of(new PlanStep("调研", "", 0)), null);

        assertThat(writeTool.calls.get()).isZero();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo("ok");
        assertThat(results.get(0).text()).isEqualTo("调研结论");
    }

    @Test
    void executesAllowlistedReadOnlyTools() {
        FakeTool echoTool = new FakeTool("echo", true);
        ToolExecutor tools = new ToolExecutor(List.of(echoTool));

        Map<String, List<String>> replies = new LinkedHashMap<>();
        replies.put("回显", List.of(
                "<tool_call>{\"tool\": \"echo\", \"args\": {\"text\": \"hi\"}}</tool_call>",
                "回显完成"));
        MarkerProvider provider = new MarkerProvider(replies, null);

        List<SubAgentResult> results = executor(provider, tools, true, 1,
                List.of("echo")).execute(ctx(),
                ExecutionPlan.of(new PlanStep("回显", "", 0)), null);

        assertThat(echoTool.calls.get()).isEqualTo(1);
        assertThat(results.get(0).status()).isEqualTo("ok");
        assertThat(results.get(0).text()).isEqualTo("回显完成");
    }

    @Test
    void isolatesStepFailuresWithoutAbortingOthers() {
        Map<String, List<String>> replies = new LinkedHashMap<>();
        replies.put("好步骤", List.of("成功结果"));
        replies.put("坏步骤", List.of("ERR: 模型挂了"));
        MarkerProvider provider = new MarkerProvider(replies, null);

        List<SubAgentResult> results = executor(provider, new ToolExecutor(List.of()),
                true, 2, List.of())
                .execute(ctx(), ExecutionPlan.of(
                        new PlanStep("好步骤", "", 1),
                        new PlanStep("坏步骤", "", 1)), null);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(SubAgentResult::status)
                .containsExactlyInAnyOrder("ok", "error");
        SubAgentResult failed = results.stream()
                .filter(r -> "error".equals(r.status())).findFirst().orElseThrow();
        assertThat(failed.error()).contains("模型挂了");
    }

    @Test
    void returnsEmptyWhenDisabled() {
        MarkerProvider provider = new MarkerProvider(Map.of(), null);
        assertThat(executor(provider, new ToolExecutor(List.of()), false, 1, List.of())
                .execute(ctx(), ExecutionPlan.of(
                        new PlanStep("步骤A", "", 1),
                        new PlanStep("步骤B", "", 1)), null)).isEmpty();
    }
}

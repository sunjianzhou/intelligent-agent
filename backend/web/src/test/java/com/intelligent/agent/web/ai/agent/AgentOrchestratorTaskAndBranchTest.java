package com.intelligent.agent.web.ai.agent;

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
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentOrchestrator Task 4 行为测试：
 * 任务标记事件、分支失败停止、多模态图片透传。
 */
class AgentOrchestratorTaskAndBranchTest {

    /** 捕获每次 complete 的 ChatTurn。 */
    static class CapturingProvider implements LlmProvider {
        final List<String> replies;
        final AtomicReference<ChatTurn> lastTurn = new AtomicReference<>();
        final CopyOnWriteArrayList<ChatTurn> allTurns = new CopyOnWriteArrayList<>();

        CapturingProvider(List<String> replies) {
            this.replies = replies;
        }

        @Override
        public String name() {
            return "capture";
        }

        @Override
        public Flux<ModelEvent> stream(ChatTurn turn) {
            return Flux.just(ModelEvent.token("ok"), ModelEvent.done(Map.of()));
        }

        @Override
        public Mono<String> complete(ChatTurn turn) {
            lastTurn.set(turn);
            allTurns.add(turn);
            int index = Math.min(allTurns.size() - 1, replies.size() - 1);
            return Mono.just(replies.get(index));
        }
    }

    static class FailTool implements AgentTool {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("fail", "总是失败", true, null, null);
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            throw new IllegalStateException("connection refused");
        }
    }

    /** 计数用的幂等回显工具（TD-19① 重复调用断路器测试用）。 */
    static class CountingEchoTool implements AgentTool {
        final AtomicInteger executions = new AtomicInteger();

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("echo", "回显", true, null, null);
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            executions.incrementAndGet();
            return "echo:" + arguments.getOrDefault("text", "");
        }
    }

    private final ToolExecutor failingTools = new ToolExecutor(List.of(new FailTool()));
    private final CountingEchoTool countingEcho = new CountingEchoTool();
    private final ToolExecutor echoTools = new ToolExecutor(List.of(countingEcho));
    private final AtomicInteger echoExecutions = countingEcho.executions;

    @Test
    void emitsTaskEventsAndStripsSentinelsFromStream() {
        CapturingProvider provider = new CapturingProvider(List.of(
                "完成 [TASK_DONE:task-001] 了，[TASK_BLOCKED:task-002] 无法继续"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()), new ToolExecutor(List.of()));
        AgentRequestContext ctx = new AgentRequestContext(
                "u1", "hi", null, null, "proj-1", null, true, true, "web", Map.of(),
                null, null, false);

        StepVerifier.create(orchestrator.stream(ctx))
                .expectNextMatches(e -> e.type().equals("token")
                        && !String.valueOf(e.data()).contains("TASK_DONE"))
                .expectNextMatches(e -> e.type().equals("task_update"))
                .expectNextMatches(e -> e.type().equals("task_blocked"))
                .expectNextMatches(e -> e.type().equals("done"))
                .verifyComplete();
    }

    @Test
    void completeStripsTaskSentinels() {
        CapturingProvider provider = new CapturingProvider(List.of(
                "好的 [TASK_DONE:task-9]"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()), new ToolExecutor(List.of()));

        StepVerifier.create(orchestrator.complete(AgentRequestContext.of("u1", "hi")))
                .expectNext("好的")
                .verifyComplete();
    }

    @Test
    void stopsLoopOnSameToolErrorThreeTimes() {
        // TD-19：同工具+同参数的第二次调用会被重复调用断路器拦截，所以这里每轮换参数，
        // 只考察"同工具同错误 ≥3 次"这条信号本身。
        CapturingProvider provider = new CapturingProvider(List.of(
                "<tool_call>{\"tool\": \"fail\", \"args\": {\"n\": \"1\"}}</tool_call>",
                "<tool_call>{\"tool\": \"fail\", \"args\": {\"n\": \"2\"}}</tool_call>",
                "<tool_call>{\"tool\": \"fail\", \"args\": {\"n\": \"3\"}}</tool_call>",
                "不应到达"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()), failingTools);

        String answer = orchestrator.complete(AgentRequestContext.of("u1", "go")).block();

        assertThat(answer).contains("分支失败").contains("same_tool_same_error");
        assertThat(provider.allTurns.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void blocksRepeatedIdenticalToolCall() {
        // TD-19①：同一工具 + 同一参数的重复调用直接拦截，不再执行（防止空转/记忆污染）
        CapturingProvider provider = new CapturingProvider(List.of(
                "<tool_call>{\"tool\": \"echo\", \"args\": {\"text\": \"x\"}}</tool_call>",
                "<tool_call>{\"tool\": \"echo\", \"args\": {\"text\": \"x\"}}</tool_call>",
                "完成"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()), echoTools);

        String answer = orchestrator.complete(AgentRequestContext.of("u1", "go")).block();

        assertThat(answer).isEqualTo("完成");
        assertThat(echoExecutions.get()).isEqualTo(1);
        assertThat(provider.allTurns).hasSize(3);
    }

    /** 计数用的 store_memory 替身（TD-21 写入上限测试用）。 */
    static class CountingStoreTool implements AgentTool {
        final AtomicInteger executions = new AtomicInteger();

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("store_memory", "写记忆", true, null, null);
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            return Map.of("success", true,
                    "memory_id", "m" + executions.incrementAndGet());
        }
    }

    @Test
    void capsMemoryWritesPerRequest() {
        // TD-21：内容各不相同的自污染写入（横幅变体）靠同参数去重拦不住，按次数兜底
        CountingStoreTool store = new CountingStoreTool();
        CapturingProvider provider = new CapturingProvider(List.of(
                "<tool_call>{\"tool\": \"store_memory\", \"args\": {\"content\": \"a\"}}</tool_call>",
                "<tool_call>{\"tool\": \"store_memory\", \"args\": {\"content\": \"b\"}}</tool_call>",
                "<tool_call>{\"tool\": \"store_memory\", \"args\": {\"content\": \"c\"}}</tool_call>",
                "<tool_call>{\"tool\": \"store_memory\", \"args\": {\"content\": \"d\"}}</tool_call>",
                "完成"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                new ToolExecutor(List.of(store)));

        String answer = orchestrator.complete(AgentRequestContext.of("u1", "go")).block();

        assertThat(answer).isEqualTo("完成");
        assertThat(store.executions.get()).isEqualTo(AgentOrchestrator.MAX_MEMORY_WRITES);
    }

    @Test
    void trustedToolResultsAreNotBannerWrapped() {
        // TD-21：store_memory 的收据是本服务自己生成的，不该被"不可信数据"横幅包住——
        // 包住之后小模型会把横幅当内容再写一遍，5 轮额度全耗在自污染上
        CountingStoreTool store = new CountingStoreTool();
        CapturingProvider provider = new CapturingProvider(List.of(
                "<tool_call>{\"tool\": \"store_memory\", \"args\": {\"content\": \"我住在上海\"}}</tool_call>",
                "记住了"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()),
                new ToolExecutor(List.of(store)));

        orchestrator.complete(AgentRequestContext.of("u1", "go")).block();

        String toolMessage = provider.allTurns.get(1).messages().stream()
                .filter(m -> "tool".equals(m.role()))
                .map(m -> m.content())
                .findFirst().orElseThrow();
        assertThat(toolMessage).doesNotContain("不可信数据").contains("memory_id");
    }

    @Test
    void failedToolResultsAreNotBannerWrapped() {
        // TD-21：失败文案也是本服务自己写的错误说明；包横幅会让模型把错误文本当新输入再调
        CapturingProvider provider = new CapturingProvider(List.of(
                "<tool_call>{\"tool\": \"fail\", \"args\": {\"n\": \"1\"}}</tool_call>",
                "失败了"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()), failingTools);

        orchestrator.complete(AgentRequestContext.of("u1", "go")).block();

        String toolMessage = provider.allTurns.get(1).messages().stream()
                .filter(m -> "tool".equals(m.role()))
                .map(m -> m.content())
                .findFirst().orElseThrow();
        assertThat(toolMessage).doesNotContain("不可信数据").contains("connection refused");
    }

    @Test
    void passesImageToProviderTurn() {
        CapturingProvider provider = new CapturingProvider(List.of("ok"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new LlmProviderRouter(provider, null, List.of()), new ToolExecutor(List.of()));
        AgentRequestContext ctx = new AgentRequestContext(
                "u1", "看图", null, null, null, null, true, true, "web", Map.of(),
                "aGVsbG8=", null, false);

        orchestrator.complete(ctx).block();

        assertThat(provider.lastTurn.get().images()).containsExactly("aGVsbG8=");
    }
}

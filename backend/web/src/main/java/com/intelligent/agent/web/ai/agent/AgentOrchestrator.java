package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProvider;
import com.intelligent.agent.web.ai.llm.LlmProviderException;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.ai.memory.AgentContext;
import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.tool.TextToolCallParser;
import com.intelligent.agent.web.ai.tool.ToolCall;
import com.intelligent.agent.web.ai.tool.ToolExecutionContext;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.ai.tool.ToolResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 本地 ReAct 编排器：
 * <ol>
 *   <li>非流式首轮（带工具）最多执行 {@link #DEFAULT_MAX_TOOL_ROUNDS} 轮；
 *       文本工具调用通过 {@link TextToolCallParser} 从回复中提取并执行；</li>
 *   <li>无工具调用时复用首轮内容（避免对本地模型二次推理）；</li>
 *   <li>有工具调用时以流式方式产出最终回答，并在流前发出 tool_calls_done 事件。</li>
 * </ol>
 */
public class AgentOrchestrator {

    public static final int DEFAULT_MAX_TOOL_ROUNDS = 5;

    private final LlmProviderRouter router;
    private final ToolExecutor toolExecutor;
    private final TextToolCallParser toolCallParser;
    private final ConversationMemoryService memoryService;
    private final int maxToolRounds;

    public AgentOrchestrator(LlmProviderRouter router, ToolExecutor toolExecutor) {
        this(router, toolExecutor, DEFAULT_MAX_TOOL_ROUNDS);
    }

    public AgentOrchestrator(LlmProviderRouter router, ToolExecutor toolExecutor, int maxToolRounds) {
        this(router, toolExecutor, null, maxToolRounds);
    }

    public AgentOrchestrator(LlmProviderRouter router, ToolExecutor toolExecutor,
                             ConversationMemoryService memoryService, int maxToolRounds) {
        this.router = Objects.requireNonNull(router, "router must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
        this.toolCallParser = new TextToolCallParser();
        this.memoryService = memoryService;
        this.maxToolRounds = maxToolRounds;
    }

    public Flux<ModelEvent> stream(AgentRequestContext context) {
        Objects.requireNonNull(context, "context must not be null");
        AgentContext memory = loadMemory(context);
        if (memory.cachedAnswer().isPresent()) {
            String cached = memory.cachedAnswer().get();
            return Flux.concat(
                            Flux.just(ModelEvent.token(cached)),
                            Flux.just(ModelEvent.done(Map.of())))
                    .doOnComplete(() -> recordTurn(context, cached));
        }
        StringBuilder tokens = new StringBuilder();
        return Flux.defer(() -> runToolRounds(context, initialMessages(context, memory), 0, List.of())
                        .flatMapMany(state -> streamFinal(context, state)))
                .doOnNext(event -> {
                    if ("token".equals(event.type())) {
                        tokens.append(event.data());
                    }
                })
                .doOnComplete(() -> recordTurn(context, tokens.toString()));
    }

    public Mono<String> complete(AgentRequestContext context) {
        Objects.requireNonNull(context, "context must not be null");
        AgentContext memory = loadMemory(context);
        if (memory.cachedAnswer().isPresent()) {
            String cached = memory.cachedAnswer().get();
            return Mono.just(cached).doOnSuccess(answer -> recordTurn(context, answer));
        }
        return Mono.defer(() -> runToolRounds(context, initialMessages(context, memory), 0, List.of())
                        .flatMap(state -> completeFinal(context, state)))
                .doOnSuccess(answer -> recordTurn(context, answer));
    }

    private AgentContext loadMemory(AgentRequestContext ctx) {
        return memoryService == null ? AgentContext.empty() : memoryService.loadContext(ctx);
    }

    private void recordTurn(AgentRequestContext ctx, String answer) {
        if (memoryService != null) {
            memoryService.recordTurn(ctx, answer);
        }
    }

    private List<ChatMessage> initialMessages(AgentRequestContext ctx, AgentContext memory) {
        List<ChatMessage> messages = new ArrayList<>();
        if (ctx.persona() != null && !ctx.persona().isBlank()) {
            messages.add(ChatMessage.system("你是 " + ctx.persona() + "。"));
        }
        if (ctx.useMemory()) {
            if (!memory.longTermRecall().isEmpty()) {
                StringBuilder recall = new StringBuilder("[LONG-TERM MEMORY]\n");
                for (MemoryRecord record : memory.longTermRecall()) {
                    recall.append("- ").append(record.content()).append('\n');
                }
                messages.add(ChatMessage.system(recall.toString().trim()));
            }
            if (!memory.projectContext().isBlank()) {
                messages.add(ChatMessage.system("[PROJECT CONTEXT]\n" + memory.projectContext()));
            }
            messages.addAll(memory.history());
        }
        messages.add(ChatMessage.user(ctx.message()));
        return messages;
    }

    private Mono<ReActState> runToolRounds(AgentRequestContext ctx, List<ChatMessage> messages,
                                           int round, List<ToolCall> executedCalls) {
        if (round >= maxToolRounds) {
            return Mono.just(new ReActState(messages, "", true, executedCalls, false));
        }
        LlmProvider provider = router.forUser(ctx.userId(), ctx.model());
        return provider.complete(buildTurn(ctx, messages))
                .flatMap(content -> handleRound(ctx, messages, content, executedCalls))
                .flatMap(state -> state.continueLoop()
                        ? runToolRounds(ctx, state.messages(), round + 1, state.executedCalls())
                        : Mono.just(state));
    }

    private Mono<ReActState> handleRound(AgentRequestContext ctx, List<ChatMessage> messages,
                                         String content, List<ToolCall> executedCalls) {
        // toolsRan 是粘性的：只要本轮或之前任一工具轮执行过工具，后续轮次不得再复用
        // 首轮内容跳过 tool_calls_done / 最终流式回答。
        boolean toolsRan = !executedCalls.isEmpty();
        if (!ctx.useTools()) {
            return Mono.just(new ReActState(
                    appendAssistant(messages, content), content, toolsRan, executedCalls, false));
        }
        List<ToolCall> calls = toolCallParser.parse(content);
        if (calls.isEmpty()) {
            return Mono.just(new ReActState(
                    appendAssistant(messages, content), content, toolsRan, executedCalls, false));
        }
        List<ChatMessage> next = appendAssistant(messages, content);
        return Mono.fromCallable(() -> {
            ToolExecutionContext execCtx = ToolExecutionContext.of(ctx.userId(), "user", false);
            List<ToolCall> executed = new ArrayList<>(executedCalls);
            for (ToolCall call : calls) {
                ToolResult result = toolExecutor.execute(call, execCtx);
                executed.add(call);
                String text = result.data() != null ? String.valueOf(result.data())
                        : (result.error() != null ? result.error() : result.status());
                next.add(ChatMessage.user(
                        "[工具执行结果]\n" + text + "\n\n请基于以上结果继续。"));
            }
            return new ReActState(next, "", true, executed, true);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ModelEvent> streamFinal(AgentRequestContext ctx, ReActState state) {
        if (!state.toolsRan() && state.content() != null && !state.content().isBlank()) {
            return Flux.just(ModelEvent.token(state.content()), ModelEvent.done(Map.of()));
        }
        List<ChatMessage> finalMessages = finalizeMessages(state.messages(), state.toolsRan());
        Flux<ModelEvent> answer = router.forUser(ctx.userId(), ctx.model())
                .stream(buildTurn(ctx, finalMessages))
                .onErrorResume(e -> Flux.just(ModelEvent.error(safeMessage(e))));
        if (state.executedCalls().isEmpty()) {
            return answer;
        }
        return Flux.concat(
                Flux.just(ModelEvent.toolCallsDone(state.executedCalls())),
                answer);
    }

    private Mono<String> completeFinal(AgentRequestContext ctx, ReActState state) {
        if (!state.toolsRan() && state.content() != null && !state.content().isBlank()) {
            return Mono.just(state.content());
        }
        List<ChatMessage> finalMessages = finalizeMessages(state.messages(), state.toolsRan());
        return router.forUser(ctx.userId(), ctx.model())
                .complete(buildTurn(ctx, finalMessages))
                .onErrorResume(e -> Mono.just(safeMessage(e)));
    }

    private List<ChatMessage> finalizeMessages(List<ChatMessage> messages, boolean toolsRan) {
        List<ChatMessage> out = new ArrayList<>(messages);
        if (toolsRan) {
            out.add(ChatMessage.system(
                    "工具已执行完毕，结果已在上方。请直接用中文回答用户的问题，"
                            + "不要再输出任何工具调用格式，直接给出自然语言回答。"));
        }
        return out;
    }

    private ChatTurn buildTurn(AgentRequestContext ctx, List<ChatMessage> messages) {
        return new ChatTurn(ctx.userId(), ctx.model(), messages, ctx.options());
    }

    private static List<ChatMessage> appendAssistant(List<ChatMessage> messages, String content) {
        List<ChatMessage> next = new ArrayList<>(messages);
        next.add(ChatMessage.assistant(content));
        return next;
    }

    private static String safeMessage(Throwable e) {
        if (e instanceof LlmProviderException || e.getMessage() == null) {
            return e instanceof LlmProviderException
                    ? e.getMessage() : "internal agent error";
        }
        return e.getMessage();
    }

    /** ReAct 状态：消息列表 + 当前无工具回复内容 + 是否执行过工具 + 已执行调用。 */
    private record ReActState(List<ChatMessage> messages, String content, boolean toolsRan,
                              List<ToolCall> executedCalls, boolean continueLoop) {
    }
}

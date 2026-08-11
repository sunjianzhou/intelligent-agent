package com.intelligent.agent.web.ai.tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 有界工具执行内核：
 * <ul>
 *   <li>requiredRole 角色校验；</li>
 *   <li>单请求最多 {@code maxRounds} 轮工具调用；</li>
 *   <li>按 ToolDefinition.timeout 限制单次执行时长。</li>
 * </ul>
 */
public class ToolExecutor {

    private final Map<String, AgentTool> tools;
    private final int maxRounds;
    private final ExecutorService timeoutExecutor;

    public ToolExecutor(List<AgentTool> tools) {
        this(tools, 5);
    }

    public ToolExecutor(List<AgentTool> tools, int maxRounds) {
        this.tools = tools == null ? Map.of() : tools.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableMap(
                        t -> t.definition().name(), t -> t, (a, b) -> a));
        this.maxRounds = maxRounds;
        this.timeoutExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "tool-executor");
            t.setDaemon(true);
            return t;
        });
    }

    public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        Objects.requireNonNull(call, "call must not be null");
        Objects.requireNonNull(context, "context must not be null");

        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.notFound("tool not found: " + call.name());
        }

        ToolDefinition definition = tool.definition();
        if (definition.requiredRole() != null && !definition.requiredRole().isBlank()
                && !definition.requiredRole().equals(context.role())) {
            return ToolResult.denied("tool requires role: " + definition.requiredRole());
        }
        if (context.currentRound() >= maxRounds) {
            return ToolResult.error("tool round limit exceeded (max " + maxRounds + ")");
        }
        context.nextRound();

        long start = System.nanoTime();
        try {
            Object result;
            if (definition.timeout() != null) {
                Future<Object> future =
                        timeoutExecutor.submit(() -> tool.execute(call.arguments()));
                try {
                    result = future.get(definition.timeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    return ToolResult.timeout("tool timed out after "
                            + definition.timeout().toMillis() + "ms: " + call.name());
                }
            } else {
                result = tool.execute(call.arguments());
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return new ToolResult(ToolResult.SUCCESS, result, null, elapsedMs);
        } catch (Exception e) {
            return ToolResult.error("tool failed: " + e.getMessage());
        }
    }

    /** 已注册工具的定义列表（/api/tools/list 与 LLM 工具提示用）。 */
    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }
}

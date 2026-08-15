package com.intelligent.agent.web.ai.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
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
        this.tools = new ConcurrentHashMap<>();
        this.maxRounds = maxRounds;
        if (tools != null) {
            for (AgentTool tool : tools) {
                register(tool);
            }
        }
        this.timeoutExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "tool-executor");
            t.setDaemon(true);
            return t;
        });
    }

    /** 运行期注册工具（G2：MCP 服务器连接成功后动态加入）。 */
    public void register(AgentTool tool) {
        if (tool != null && tool.definition() != null && tool.definition().name() != null) {
            tools.put(tool.definition().name(), tool);
        }
    }

    /** 运行期移除工具（G2：MCP 服务器断开时清理）。 */
    public void unregister(String name) {
        if (name != null) {
            tools.remove(name);
        }
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
        int slot = context.acquireSlot();
        if (slot > maxRounds) {
            return ToolResult.error("tool round limit exceeded (max " + maxRounds + ")");
        }

        long start = System.nanoTime();
        try {
            Object result;
            if (definition.timeout() != null) {
                CompletableFuture<Object> future = CompletableFuture.supplyAsync(
                        () -> tool.execute(call.arguments(), context), timeoutExecutor);
                try {
                    result = future.get(definition.timeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    return ToolResult.timeout("tool timed out after "
                            + definition.timeout().toMillis() + "ms: " + call.name());
                }
            } else {
                result = tool.execute(call.arguments(), context);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return new ToolResult(ToolResult.SUCCESS, result, null, elapsedMs);
        } catch (Exception e) {
            return ToolResult.error("tool failed: " + e.getMessage());
        }
    }

    /**
     * 并行执行一组工具调用（原生 function calling 多工具场景）。
     * <p>
     * 每个调用复用 {@link #execute} 的超时/角色/轮次语义；
     * 结果按入参顺序返回（并行执行但收集顺序确定）。
     */
    public List<ToolResult> executeParallel(List<ToolCall> calls, ToolExecutionContext context) {
        Objects.requireNonNull(calls, "calls must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (calls.isEmpty()) {
            return List.of();
        }
        ToolResult[] results = new ToolResult[calls.size()];
        List<CompletableFuture<Void>> futures = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            int idx = i;
            futures.add(CompletableFuture.runAsync(
                    () -> results[idx] = execute(calls.get(idx), context), timeoutExecutor));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            // 兜底：任何未捕获异常映射为 error，保持顺序与结果完整性
        }
        List<ToolResult> out = new ArrayList<>(calls.size());
        for (ToolResult result : results) {
            out.add(result == null
                    ? ToolResult.error("tool execution failed")
                    : result);
        }
        return out;
    }

    /** 已注册工具的定义列表（/api/tools/list 与 LLM 工具提示用）。 */
    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }
}

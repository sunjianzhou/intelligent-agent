package com.intelligent.agent.web.ai.tool;

import java.util.Map;

/**
 * 可执行工具契约：定义（元数据）+ 执行体。
 * 工具实现不得持有请求级可变状态。
 */
public interface AgentTool {

    ToolDefinition definition();

    Object execute(Map<String, Object> arguments);

    /**
     * 带执行上下文的入口（2026-08-15 架构调整）：需要 userId / role 的工具
     * （store_memory / search_memories / create_reminder 等）覆写此方法；
     * 无状态工具沿用默认桥接到 {@link #execute(Map)}。
     *
     * @param arguments 工具参数
     * @param context   单次请求执行上下文（userId / role / 轮次）
     */
    default Object execute(Map<String, Object> arguments, ToolExecutionContext context) {
        return execute(arguments);
    }
}

package com.intelligent.agent.web.ai.tool;

import java.util.Map;

/**
 * 可执行工具契约：定义（元数据）+ 执行体。
 * 工具实现不得持有请求级可变状态。
 */
public interface AgentTool {

    ToolDefinition definition();

    Object execute(Map<String, Object> arguments);
}

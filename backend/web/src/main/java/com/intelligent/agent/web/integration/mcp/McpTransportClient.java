package com.intelligent.agent.web.integration.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 传输客户端抽象（G2）：HTTP JSON-RPC 与 stdio 两种传输的统一契约。
 * 每个已连接服务器持有一个长期客户端（session 池化复用），断开时 close。
 */
public interface McpTransportClient {

    boolean initialize(String protocolVersion);

    List<McpClient.McpToolInfo> listTools();

    Map<String, Object> callTool(String toolName, Map<String, Object> arguments);

    String sessionId();

    void close();
}

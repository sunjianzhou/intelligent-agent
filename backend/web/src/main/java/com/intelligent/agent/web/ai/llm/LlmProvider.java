package com.intelligent.agent.web.ai.llm;

import com.intelligent.agent.web.ai.tool.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 模型无关的 LLM 提供方契约。
 * <p>
 * 实现负责把各自的协议（Ollama / OpenAI 兼容 / 未来其他）映射为
 * {@link ModelEvent} 流，凭据与协议细节不得泄漏到 {@code ai.llm} 之外。
 */
public interface LlmProvider {

    /** 提供方标识，用于路由与日志（如 "ollama" / "cloud"）。 */
    String name();

    /** 流式生成，逐 token 产出事件，结束时发出 done 事件。 */
    Flux<ModelEvent> stream(ChatTurn turn);

    /** 非流式生成，返回完整回复文本。 */
    Mono<String> complete(ChatTurn turn);

    /**
     * 非流式生成（带原生工具 schema）。
     * <p>
     * 支持原生 function calling 的实现应解析消息中的 {@code tool_calls}；
     * 不支持时默认降级为 {@link #complete(ChatTurn)}（由编排层用文本解析兜底）。
     *
     * @param turn  对话请求
     * @param tools 工具定义（转换为协议 tools 字段；空 = 不发送）
     */
    default Mono<LlmResponse> completeWithTools(ChatTurn turn, List<ToolDefinition> tools) {
        return complete(turn).map(content -> new LlmResponse(content, List.of()));
    }
}

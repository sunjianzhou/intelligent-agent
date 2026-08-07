package com.intelligent.agent.web.ai.llm;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
}

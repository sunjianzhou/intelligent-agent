package com.intelligent.agent.web.ai.llm;

/**
 * LLM provider 调用异常。
 * <p>
 * 所有对外消息必须经过凭据脱敏（{@link AbstractHttpLlmProvider#redact}），
 * 不得包含 API Key / Authorization 头等敏感信息。
 */
public class LlmProviderException extends RuntimeException {

    public LlmProviderException(String message) {
        super(message);
    }

    public LlmProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}

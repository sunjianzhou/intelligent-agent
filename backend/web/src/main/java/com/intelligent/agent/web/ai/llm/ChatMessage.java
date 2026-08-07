package com.intelligent.agent.web.ai.llm;

/**
 * 模型无关的单条对话消息。
 *
 * @param role    消息角色：system / user / assistant / tool
 * @param content 消息内容
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}

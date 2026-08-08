package com.intelligent.agent.client.chat;

/**
 * 解析后的 SSE 事件：type 与 data（data 保持原始 JSON 字符串）。
 */
public record SseEvent(String type, String data) {
}

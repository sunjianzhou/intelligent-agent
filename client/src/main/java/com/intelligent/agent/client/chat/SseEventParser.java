package com.intelligent.agent.client.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 解析后端 /api/chat/stream 的 SSE 行："data: {type, data}"。
 * 非 data 行返回 null（调用方跳过）。
 */
public class SseEventParser {

    private static final String DATA_PREFIX = "data:";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SseEvent parse(String line) {
        if (line == null || !line.startsWith(DATA_PREFIX)) {
            return null;
        }
        String json = line.substring(DATA_PREFIX.length()).trim();
        if (json.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String type = node.path("type").asText("");
            JsonNode dataNode = node.get("data");
            String data = dataNode == null ? ""
                    : dataNode.isTextual() ? dataNode.asText() : dataNode.toString();
            if (type.isEmpty()) {
                return null;
            }
            return new SseEvent(type, data);
        } catch (Exception e) {
            return null;
        }
    }
}

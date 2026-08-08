package com.intelligent.agent.client.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 事件解析测试（Plan 3 / Task 2）：兼容后端 /api/chat/stream 的
 * "data: {type, data}" 单行事件形状。
 */
class SseEventParserTest {

    private final SseEventParser parser = new SseEventParser();

    @Test
    void parsesTokenEvent() {
        SseEvent event = parser.parse("data: {\"type\":\"token\",\"data\":\"hi\"}");

        assertThat(event.type()).isEqualTo("token");
        assertThat(event.data()).isEqualTo("hi");
    }

    @Test
    void parsesDoneEventWithEmptyData() {
        SseEvent event = parser.parse("data: {\"type\":\"done\",\"data\":{}}");

        assertThat(event.type()).isEqualTo("done");
        assertThat(event.data()).isEqualTo("{}");
    }

    @Test
    void ignoresNonDataLines() {
        assertThat(parser.parse("event: token")).isNull();
        assertThat(parser.parse("")).isNull();
    }

    @Test
    void parsesErrorEvent() {
        SseEvent event = parser.parse("data: {\"type\":\"error\",\"data\":\"boom\"}");

        assertThat(event.type()).isEqualTo("error");
        assertThat(event.data()).isEqualTo("boom");
    }
}

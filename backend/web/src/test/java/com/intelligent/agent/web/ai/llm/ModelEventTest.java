package com.intelligent.agent.web.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 ModelEvent 序列化形状与既有 SSE 协议一致（type/data），
 * 且事件类型被限定在公开契约内：token / tool_call_start / tool_call /
 * tool_calls_done / done / error。
 */
class ModelEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void tokenUsesExistingSseShape() throws Exception {
        assertThat(mapper.writeValueAsString(ModelEvent.token("你好")))
                .contains("\"type\":\"token\"")
                .contains("\"data\":\"你好\"");
    }

    @Test
    void everyEventTypeSerializesWithTypeAndData() throws Exception {
        String[] samples = {
                mapper.writeValueAsString(ModelEvent.toolCallStart(List.of("t1"))),
                mapper.writeValueAsString(ModelEvent.toolCall(List.of("t1"))),
                mapper.writeValueAsString(ModelEvent.toolCallsDone(List.of("t1"))),
                mapper.writeValueAsString(ModelEvent.done(Map.of("status", "ok"))),
                mapper.writeValueAsString(ModelEvent.error("boom"))
        };
        for (String json : samples) {
            assertThat(json).contains("\"type\":").contains("\"data\":");
        }
    }

    @Test
    void eventTypesAreLimitedToTheContract() {
        assertThat(ModelEvent.token("hi").type()).isEqualTo("token");
        assertThat(ModelEvent.toolCallStart("x").type()).isEqualTo("tool_call_start");
        assertThat(ModelEvent.toolCall("x").type()).isEqualTo("tool_call");
        assertThat(ModelEvent.toolCallsDone("x").type()).isEqualTo("tool_calls_done");
        assertThat(ModelEvent.done("x").type()).isEqualTo("done");
        assertThat(ModelEvent.error("x").type()).isEqualTo("error");
    }
}

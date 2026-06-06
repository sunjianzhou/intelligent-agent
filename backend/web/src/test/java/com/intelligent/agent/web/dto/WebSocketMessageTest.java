package com.intelligent.agent.web.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class WebSocketMessageTest {

    @Test
    void connectionEstablished_hasCorrectTypeAndRequestId() {
        WebSocketMessage msg = WebSocketMessage.connectionEstablished("req-001");
        assertThat(msg.getType()).isEqualTo("connection_established");
        assertThat(msg.getRequestId()).isEqualTo("req-001");
        assertThat(msg.getMessage()).isNotBlank();
    }

    @Test
    void thinking_hasThinkingType() {
        WebSocketMessage msg = WebSocketMessage.thinking("req-002");
        assertThat(msg.getType()).isEqualTo("thinking");
        assertThat(msg.getRequestId()).isEqualTo("req-002");
    }

    @Test
    void chatResponse_carriesMessageAndResponseTime() {
        WebSocketMessage msg = WebSocketMessage.chatResponse("req-003", "hello", 1.23);
        assertThat(msg.getType()).isEqualTo("chat_response");
        assertThat(msg.getMessage()).isEqualTo("hello");
        assertThat(msg.getResponseTime()).isEqualTo(1.23);
        assertThat(msg.getRequestId()).isEqualTo("req-003");
    }

    @Test
    void error_hasErrorTypeAndMessage() {
        WebSocketMessage msg = WebSocketMessage.error("req-004", "something went wrong");
        assertThat(msg.getType()).isEqualTo("error");
        assertThat(msg.getMessage()).isEqualTo("something went wrong");
        assertThat(msg.getRequestId()).isEqualTo("req-004");
    }

    @Test
    void messageType_constants_matchProtocol() {
        // 协议常量回归：任何重命名都会触发这里的失败，提醒同步前端和 Python 端
        assertThat(WebSocketMessageType.CHAT_TOKEN).isEqualTo("chat_token");
        assertThat(WebSocketMessageType.CHAT_DONE).isEqualTo("chat_done");
        assertThat(WebSocketMessageType.THINKING).isEqualTo("thinking");
        assertThat(WebSocketMessageType.TOOL_CALLS_DONE).isEqualTo("tool_calls_done");
        assertThat(WebSocketMessageType.ERROR).isEqualTo("error");
        assertThat(WebSocketMessageType.NOTIFICATION).isEqualTo("notification");
        assertThat(WebSocketMessageType.TASK_UPDATE).isEqualTo("task_update");
        assertThat(WebSocketMessageType.TASK_BLOCKED).isEqualTo("task_blocked");
        assertThat(WebSocketMessageType.PROTOCOL_VERSION).isEqualTo(1);
    }

    @Test
    void chatResponse_nullResponseTime_isAllowed() {
        WebSocketMessage msg = WebSocketMessage.chatResponse("req-005", "hi", null);
        assertThat(msg.getResponseTime()).isNull();
    }
}

package com.intelligent.agent.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 描述：
 *
 * @author lin miao
 * @date 2026/5/1
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebSocketMessage {
    private String type;           // 消息类型
    private String requestId;      // 请求ID
    private String message;        // 消息内容
    private Object data;           // 附加数据
    private Double responseTime;   // 响应时间
    private String timestamp;      // 时间戳

    public static WebSocketMessage connectionEstablished(String requestId) {
        WebSocketMessage msg = new WebSocketMessage();
        msg.setType("connection_established");
        msg.setRequestId(requestId);
        msg.setMessage("WebSocket连接已建立");
        return msg;
    }

    public static WebSocketMessage thinking(String requestId) {
        WebSocketMessage msg = new WebSocketMessage();
        msg.setType("thinking");
        msg.setRequestId(requestId);
        msg.setMessage("正在思考...");
        return msg;
    }

    public static WebSocketMessage chatResponse(String requestId, String response, Double responseTime) {
        WebSocketMessage msg = new WebSocketMessage();
        msg.setType("chat_response");
        msg.setRequestId(requestId);
        msg.setMessage(response);
        msg.setResponseTime(responseTime);
        return msg;
    }

    public static WebSocketMessage error(String requestId, String error) {
        WebSocketMessage msg = new WebSocketMessage();
        msg.setType("error");
        msg.setRequestId(requestId);
        msg.setMessage(error);
        return msg;
    }
}

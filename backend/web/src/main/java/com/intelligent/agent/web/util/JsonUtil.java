package com.intelligent.agent.web.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/**
 * 描述：
 *
 * @author lin miao
 * @date 2026/5/1
 */
public class JsonUtil {
    private static final Logger logger = LoggerFactory.getLogger(JsonUtil.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.error("对象转JSON失败", e);
            return null;
        }
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            logger.error("JSON转对象失败: " + json, e);
            return null;
        }
    }

    // ── 工具方法: 发送 JSON 消息 ───────────────────────────────────────────────
    // WebSocketSession.sendMessage() 不是线程安全的：scheduler 推送通知与
    // streamExecutor 发送 chat_token 可能并发，synchronized(session) 保证串行。
    public static void sendJsonMessage(WebSocketSession session, Object message) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    String json = objectMapper.writeValueAsString(message);
                    logger.info("发送JSON消息: {}", json);
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            logger.error("发送WebSocket消息失败", e);
        }
    }

    public static void sendJsonMessageQuiet(WebSocketSession session, Object message) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    String json = objectMapper.writeValueAsString(message);
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            logger.error("发送WebSocket消息失败", e);
        }
    }
}

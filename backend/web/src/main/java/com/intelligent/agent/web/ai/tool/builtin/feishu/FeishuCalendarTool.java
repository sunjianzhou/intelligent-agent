package com.intelligent.agent.web.ai.tool.builtin.feishu;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.integration.feishu.FeishuChannelClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞书日历工具（TODO-110 Task 1）：action=list 查询事件、action=create 创建事件。
 * 使用 user_access_token（需用户 OAuth 授权，token 由 FeishuChannelClient 持久化）。
 */
@Slf4j
public class FeishuCalendarTool implements AgentTool {

    private final FeishuChannelClient channelClient;
    private final String feishuBase;
    private final RestTemplate restTemplate = new RestTemplate();

    public FeishuCalendarTool(FeishuChannelClient channelClient, String feishuBase) {
        this.channelClient = channelClient;
        this.feishuBase = feishuBase.endsWith("/") ? feishuBase : feishuBase + "/";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "feishu_calendar", "飞书日历工具。action=list(查询事件,需calendar_id/start_time/end_time,"
                        + "可选open_id), action=create(创建事件,需calendar_id/summary/start_time/end_time)。"
                        + " 使用用户 OAuth 授权。", false, null, null);
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        String action = String.valueOf(arguments.getOrDefault("action", "list"));
        String calendarId = String.valueOf(arguments.getOrDefault("calendar_id", ""));
        String openId = String.valueOf(arguments.getOrDefault("open_id", ""));
        String token = resolveToken(openId);
        if (token == null) {
            return Map.of("success", false, "message",
                    "需要用户 OAuth 授权（open_id 非空且已授权）");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        try {
            if ("create".equals(action)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("summary", arguments.getOrDefault("summary", ""));
                body.put("start_time", arguments.getOrDefault("start_time", ""));
                body.put("end_time", arguments.getOrDefault("end_time", ""));
                headers.setContentType(MediaType.APPLICATION_JSON);
                return post("/open-apis/calendar/v4/calendars/" + calendarId + "/events",
                        new HttpEntity<>(body, headers));
            }
            String url = "/open-apis/calendar/v4/calendars/" + calendarId + "/events?start_time="
                    + arguments.getOrDefault("start_time", "") + "&end_time="
                    + arguments.getOrDefault("end_time", "") + "&page_size="
                    + arguments.getOrDefault("page_size", 50);
            return get(url, headers);
        } catch (Exception e) {
            log.warn("飞书日历工具失败: {}", e.getMessage());
            return Map.of("success", false, "message", "飞书日历调用失败: " + e.getMessage());
        }
    }

    protected String resolveToken(String openId) {
        if (openId == null || openId.isBlank()) {
            return null;
        }
        Map<String, Object> token = channelClient.getUserToken(openId);
        Object access = token.get("access_token");
        return access == null || String.valueOf(access).isBlank() ? null : String.valueOf(access);
    }

    protected Object get(String path, HttpHeaders headers) {
        ResponseEntity<String> response = restTemplate.exchange(
                feishuBase + path.substring(1), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        return parse(response);
    }

    protected Object post(String path, HttpEntity<?> entity) {
        ResponseEntity<String> response = restTemplate.exchange(
                feishuBase + path.substring(1), HttpMethod.POST, entity, String.class);
        return parse(response);
    }

    private Object parse(ResponseEntity<String> response) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(response.getBody(), Object.class);
        } catch (Exception e) {
            return Map.of("success", false, "message", "飞书响应解析失败");
        }
    }
}

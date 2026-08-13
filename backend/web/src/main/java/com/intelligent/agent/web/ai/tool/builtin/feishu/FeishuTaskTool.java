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
import java.util.List;
import java.util.Map;

/**
 * 飞书任务工具（TODO-110 Task 1）：action=list 查询任务、action=create 创建任务、
 * action=complete 完成任务。使用 user_access_token（需用户 OAuth 授权）。
 */
@Slf4j
public class FeishuTaskTool implements AgentTool {

    private final FeishuChannelClient channelClient;
    private final String feishuBase;
    private final RestTemplate restTemplate = new RestTemplate();

    public FeishuTaskTool(FeishuChannelClient channelClient, String feishuBase) {
        this.channelClient = channelClient;
        this.feishuBase = feishuBase.endsWith("/") ? feishuBase : feishuBase + "/";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "feishu_task", "飞书任务工具。action=list(查询任务,可选open_id/tasklist_guid),"
                        + " action=create(创建任务,需summary), action=complete(完成任务,需task_guid)。"
                        + " 使用用户 OAuth 授权。", false, null, null,
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "action", Map.of("type", "string", "enum", List.of("list", "create", "complete")),
                                "open_id", Map.of("type", "string", "description", "用户 open_id"),
                                "tasklist_guid", Map.of("type", "string", "description", "任务清单 ID"),
                                "summary", Map.of("type", "string", "description", "任务标题（create 时必填）"),
                                "task_guid", Map.of("type", "string", "description", "任务 ID（complete 时必填）"),
                                "page_size", Map.of("type", "integer", "description", "分页大小，默认 50")),
                        "required", List.of("action")));
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        String action = String.valueOf(arguments.getOrDefault("action", "list"));
        String openId = String.valueOf(arguments.getOrDefault("open_id", ""));
        String token = resolveToken(openId);
        if (token == null) {
            return Map.of("success", false, "message",
                    "需要用户 OAuth 授权（open_id 非空且已授权）");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        try {
            switch (action) {
                case "create": {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("summary", arguments.getOrDefault("summary", ""));
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    ResponseEntity<String> response = restTemplate.exchange(
                            feishuBase + "open-apis/task/v2/tasks",
                            HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
                    return parse(response);
                }
                case "complete": {
                    String taskGuid = String.valueOf(arguments.getOrDefault("task_guid", ""));
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("completed", true);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    ResponseEntity<String> response = restTemplate.exchange(
                            feishuBase + "open-apis/task/v2/tasks/" + taskGuid,
                            HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
                    return parse(response);
                }
                case "list":
                default: {
                    String url = feishuBase + "open-apis/task/v2/tasks?page_size="
                            + arguments.getOrDefault("page_size", 50);
                    String tasklist = String.valueOf(arguments.getOrDefault("tasklist_guid", ""));
                    if (!tasklist.isBlank()) {
                        url += "&tasklist_guid=" + tasklist;
                    }
                    ResponseEntity<String> response = restTemplate.exchange(
                            url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                    return parse(response);
                }
            }
        } catch (Exception e) {
            log.warn("飞书任务工具失败: {}", e.getMessage());
            return Map.of("success", false, "message", "飞书任务调用失败: " + e.getMessage());
        }
    }

    private String resolveToken(String openId) {
        if (openId == null || openId.isBlank()) {
            return null;
        }
        Map<String, Object> token = channelClient.getUserToken(openId);
        Object access = token.get("access_token");
        return access == null || String.valueOf(access).isBlank() ? null : String.valueOf(access);
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

package com.intelligent.agent.web.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/**
 * 描述：
 *
 * @author lin miao
 * @date 2026/5/1
 */
@Data
@NoArgsConstructor
public class ChatRequest {
    @NotBlank(message = "消息不能为空")
    private String message;

    @JsonProperty("use_tools")
    private Boolean useTools = true;

    @JsonProperty("use_memory")
    private Boolean useMemory = true;

    @JsonProperty("project_id")
    private String projectId;

    @JsonProperty("pending_tasks")
    private List<Map<String, Object>> pendingTasks;

    /** 前端真实用户 ID（从 WebSocket session 属性中提取，不序列化到 JSON body）*/
    private transient String userId;

    public ChatRequest(String message, Boolean useTools, Boolean useMemory) {
        this.message = message;
        this.useTools = useTools;
        this.useMemory = useMemory;
    }
}
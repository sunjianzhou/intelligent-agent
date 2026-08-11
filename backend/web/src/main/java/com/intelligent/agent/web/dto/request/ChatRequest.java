package com.intelligent.agent.web.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    @Size(max = 32768, message = "消息长度不能超过 32KB")
    private String message;

    @JsonProperty("use_tools")
    private Boolean useTools = true;

    @JsonProperty("use_memory")
    private Boolean useMemory = true;

    @JsonProperty("project_id")
    private String projectId;

    @JsonProperty("pending_tasks")
    private List<Map<String, Object>> pendingTasks;

    @JsonProperty("session_id")
    private String sessionId;

    /** 请求指定的模型名；为空时由 LocalChatService 按用户偏好解析（ModelService.resolveModel）。 */
    @JsonProperty("model")
    private String model;

    /** 多模态图片（base64，不含 data URL 前缀；非多模态模型时忽略；上限 10MB 原始图片 ≈ 13.3MB base64）*/
    @JsonProperty("image_base64")
    @Size(max = 14_000_000, message = "图片大小不能超过 10MB")
    private String imageBase64;

    /** 请求来源渠道（"web"/"feishu_im"/...），决定本地 SystemPromptBuilder 是否注入私密档案段。
     *  为空时默认按 "web" 处理。*/
    private String channel;

    /** 多人会话场景标记（如飞书 "group"/"p2p"），group 时注入群聊静默规则。*/
    @JsonProperty("scene_chat_type")
    private String sceneChatType;

    /** group 场景下是否被显式 @ 提及。*/
    @JsonProperty("scene_mentioned")
    private Boolean sceneMentioned = false;

    /** 前端真实用户 ID（从 WebSocket session 属性中提取，不序列化到 JSON body）*/
    private transient String userId;

    public ChatRequest(String message, Boolean useTools, Boolean useMemory) {
        this.message = message;
        this.useTools = useTools;
        this.useMemory = useMemory;
    }
}

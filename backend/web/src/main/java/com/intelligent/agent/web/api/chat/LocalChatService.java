package com.intelligent.agent.web.api.chat;

import com.intelligent.agent.web.ai.agent.AgentOrchestrator;
import com.intelligent.agent.web.ai.agent.AgentRequestContext;
import com.intelligent.agent.web.ai.llm.ModelEvent;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.service.ModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 本地聊天服务：把 HTTP/WS 层的 {@link ChatRequest} 转换为
 * {@link AgentRequestContext}，交给 {@link AgentOrchestrator} 编排。
 */
@Service
public class LocalChatService {

    private final AgentOrchestrator orchestrator;
    private final ModelService modelService;

    public LocalChatService(AgentOrchestrator orchestrator) {
        this(orchestrator, null);
    }

    @Autowired
    public LocalChatService(AgentOrchestrator orchestrator, ModelService modelService) {
        this.orchestrator = orchestrator;
        this.modelService = modelService;
    }

    public Flux<ModelEvent> stream(ChatRequest request) {
        return orchestrator.stream(toContext(request));
    }

    public Mono<String> complete(ChatRequest request) {
        return orchestrator.complete(toContext(request));
    }

    private AgentRequestContext toContext(ChatRequest request) {
        return new AgentRequestContext(
                request.getUserId(),
                request.getMessage(),
                resolveModel(request),
                request.getPersona(),
                request.getProjectId(),
                request.getSessionId(),
                Boolean.TRUE.equals(request.getUseTools()),
                Boolean.TRUE.equals(request.getUseMemory()),
                request.getChannel(),
                Map.of(),
                request.getImageBase64(),
                request.getSceneChatType(),
                Boolean.TRUE.equals(request.getSceneMentioned()),
                request.getPendingTasks());
    }

    /** 请求显式指定模型优先；否则按用户偏好解析（ModelService），确保 per-user 模型切换生效。 */
    private String resolveModel(ChatRequest request) {
        if (request.getModel() != null && !request.getModel().isBlank()) {
            return request.getModel().trim();
        }
        return modelService == null ? null : modelService.resolveModel(request.getUserId());
    }
}

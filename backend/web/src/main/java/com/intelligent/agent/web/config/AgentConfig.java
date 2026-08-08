package com.intelligent.agent.web.config;

import com.intelligent.agent.web.ai.agent.AgentOrchestrator;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.MemoryDistillationService;
import com.intelligent.agent.web.ai.memory.SemanticResponseCache;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Agent 编排相关 Spring 装配。
 * 工具注册表当前为空（真实工具在 Plan 2 迁移时逐批注入），
 * 未配置工具时 ReAct 循环退化为纯对话。
 */
@Configuration
public class AgentConfig {

    @Bean
    public ToolExecutor toolExecutor(@Autowired(required = false) List<AgentTool> tools) {
        return new ToolExecutor(tools == null ? List.of() : tools);
    }

    @Bean
    public AgentOrchestrator agentOrchestrator(LlmProviderRouter llmProviderRouter,
                                               ToolExecutor toolExecutor,
                                               ConversationMemoryService conversationMemoryService) {
        return new AgentOrchestrator(llmProviderRouter, toolExecutor, conversationMemoryService,
                AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS);
    }

    @Bean
    public VectorMemoryRepository vectorMemoryRepository() {
        return new VectorMemoryRepository();
    }

    @Bean
    public SemanticResponseCache semanticResponseCache() {
        return new SemanticResponseCache();
    }

    @Bean
    public MemoryDistillationService memoryDistillationService() {
        return new MemoryDistillationService();
    }

    @Bean
    public ConversationMemoryService conversationMemoryService(
            VectorMemoryRepository vectorMemoryRepository,
            SemanticResponseCache semanticResponseCache,
            MemoryDistillationService memoryDistillationService) {
        return new ConversationMemoryService(
                vectorMemoryRepository, semanticResponseCache, memoryDistillationService);
    }
}

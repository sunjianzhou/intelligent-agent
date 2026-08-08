package com.intelligent.agent.web.config;

import com.intelligent.agent.web.ai.agent.AgentOrchestrator;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.MemoryDistillationService;
import com.intelligent.agent.web.ai.memory.SemanticResponseCache;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.ai.tool.builtin.CalculatorTool;
import com.intelligent.agent.web.ai.tool.builtin.TimeTool;
import com.intelligent.agent.web.ai.tool.builtin.file.FileTool;
import com.intelligent.agent.web.ai.tool.builtin.shell.ShellTool;
import com.intelligent.agent.web.ai.tool.builtin.web.WebSearchTool;
import com.intelligent.agent.web.ai.tool.builtin.database.DatabaseTool;
import com.intelligent.agent.web.ai.tool.builtin.feishu.FeishuCalendarTool;
import com.intelligent.agent.web.ai.tool.builtin.feishu.FeishuTaskTool;
import com.intelligent.agent.web.integration.feishu.FeishuChannelClient;
import org.springframework.beans.factory.annotation.Value;
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

    /** TODO-110 Task 1：内置工具注册（calculator/time/file/shell/web_search）。 */
    @Bean
    public CalculatorTool calculatorTool() {
        return new CalculatorTool();
    }

    @Bean
    public TimeTool timeTool() {
        return new TimeTool();
    }

    @Bean
    public FileTool fileTool() {
        return new FileTool();
    }

    @Bean
    public ShellTool shellTool() {
        return new ShellTool();
    }

    @Bean
    public WebSearchTool webSearchTool() {
        return new WebSearchTool();
    }

    /** TODO-110 Task 1：数据库工具（DB_* 未配置时不可用，但工具始终注册）。 */
    @Bean
    public DatabaseTool databaseTool(@Value("${db.type:}") String dbType,
                                     @Value("${db.host:}") String dbHost,
                                     @Value("${db.port:3306}") int dbPort,
                                     @Value("${db.database:}") String dbDatabase,
                                     @Value("${db.user:}") String dbUser,
                                     @Value("${db.password:}") String dbPassword) {
        return new DatabaseTool(dbType, dbHost, dbPort, dbDatabase, dbUser, dbPassword);
    }

    /** TODO-110 Task 1：飞书日历/任务工具（依赖用户 OAuth token）。 */
    @Bean
    public FeishuCalendarTool feishuCalendarTool(
            FeishuChannelClient feishuChannelClient,
            @Value("${feishu.oauth-base-url:https://open.feishu.cn}") String feishuBase) {
        return new FeishuCalendarTool(feishuChannelClient, feishuBase);
    }

    @Bean
    public FeishuTaskTool feishuTaskTool(
            FeishuChannelClient feishuChannelClient,
            @Value("${feishu.oauth-base-url:https://open.feishu.cn}") String feishuBase) {
        return new FeishuTaskTool(feishuChannelClient, feishuBase);
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

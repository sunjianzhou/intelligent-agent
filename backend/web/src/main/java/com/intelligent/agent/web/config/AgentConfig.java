package com.intelligent.agent.web.config;

import com.intelligent.agent.web.ai.agent.AgentOrchestrator;
import com.intelligent.agent.web.ai.agent.BranchFailureDetector;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.LlmExtractionService;
import com.intelligent.agent.web.ai.memory.MemoryDistillationService;
import com.intelligent.agent.web.ai.memory.SemanticResponseCache;
import com.intelligent.agent.web.ai.prompt.PromptService;
import com.intelligent.agent.web.ai.prompt.SoulLoader;
import com.intelligent.agent.web.ai.prompt.SystemPromptBuilder;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import com.intelligent.agent.web.infrastructure.vectorstore.EmbeddingService;
import com.intelligent.agent.web.infrastructure.monitoring.SystemResourceService;
import com.intelligent.agent.web.infrastructure.security.SecretCrypto;
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
import com.intelligent.agent.web.ai.tool.builtin.SchedulerTool;
import com.intelligent.agent.web.ai.tool.builtin.ImageGenTool;
import com.intelligent.agent.web.ai.tool.builtin.ChannelMessageTool;
import com.intelligent.agent.web.ai.tool.builtin.MemoryTool;
import com.intelligent.agent.web.ai.tool.builtin.SystemInfoTool;
import com.intelligent.agent.web.ai.tool.builtin.AdvancedCalculatorTool;
import com.intelligent.agent.web.ai.tool.builtin.HeartRecordTool;
import com.intelligent.agent.web.domain.role.RoleService;
import com.intelligent.agent.web.domain.task.TaskService;
import com.intelligent.agent.web.im.ChannelAdapterManager;
import com.intelligent.agent.web.integration.feishu.FeishuChannelClient;
import com.intelligent.agent.web.infrastructure.scheduler.TaskSchedulerService;
import com.intelligent.agent.web.service.ImageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Agent 编排相关 Spring 装配。
 * 工具注册表：内置工具 bean 在此装配（2026-08-15 共 14 个），
 * MCP 工具经 McpToolRegistry 追加；未装配工具时 ReAct 循环退化为纯对话。
 */
@Configuration
public class AgentConfig {

    @Bean
    public ToolExecutor toolExecutor(@Autowired(required = false) List<AgentTool> tools) {
        return new ToolExecutor(tools == null ? List.of() : tools);
    }

    /** TODO-110 Task 1 + 2026-08-15 补齐：内置工具注册（12 个 AgentTool bean）。 */
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

    /** 2026-08-15：提醒 / 定时任务工具（对齐 Python FunctionTool 五件套）。 */
    @Bean
    public SchedulerTool createReminderTool(TaskService taskService,
                                            TaskSchedulerService taskSchedulerService) {
        return new SchedulerTool(SchedulerTool.CREATE_REMINDER, taskService, taskSchedulerService);
    }

    @Bean
    public SchedulerTool createPeriodicReminderTool(TaskService taskService,
                                                    TaskSchedulerService taskSchedulerService) {
        return new SchedulerTool(SchedulerTool.CREATE_PERIODIC_REMINDER, taskService, taskSchedulerService);
    }

    @Bean
    public SchedulerTool createOnetimeAiTaskTool(TaskService taskService,
                                                 TaskSchedulerService taskSchedulerService) {
        return new SchedulerTool(SchedulerTool.CREATE_ONETIME_AI_TASK, taskService, taskSchedulerService);
    }

    @Bean
    public SchedulerTool createPeriodicAiTaskTool(TaskService taskService,
                                                  TaskSchedulerService taskSchedulerService) {
        return new SchedulerTool(SchedulerTool.CREATE_PERIODIC_AI_TASK, taskService, taskSchedulerService);
    }

    @Bean
    public SchedulerTool listTasksTool(TaskService taskService,
                                       TaskSchedulerService taskSchedulerService) {
        return new SchedulerTool(SchedulerTool.LIST_TASKS, taskService, taskSchedulerService);
    }

    /** 2026-08-15：聊天内图片生成（ComfyUI），对齐 Python ImageGenerationTool。 */
    @Bean
    public ImageGenTool imageGenTool(ImageService imageService) {
        return new ImageGenTool(imageService);
    }

    /** 2026-08-15：统一 IM 发消息工具，对齐 Python ChannelMessageTool。 */
    @Bean
    public ChannelMessageTool channelMessageTool(ChannelAdapterManager channelAdapterManager) {
        return new ChannelMessageTool(channelAdapterManager);
    }

    /** 2026-08-15：显式记忆工具（store_memory / search_memories），对齐 Python FunctionTool。 */
    @Bean
    public MemoryTool storeMemoryTool(VectorMemoryRepository vectorMemoryRepository) {
        return new MemoryTool(MemoryTool.STORE_MEMORY, vectorMemoryRepository);
    }

    @Bean
    public MemoryTool searchMemoriesTool(VectorMemoryRepository vectorMemoryRepository) {
        return new MemoryTool(MemoryTool.SEARCH_MEMORIES, vectorMemoryRepository);
    }

    /** 2026-08-15：系统信息工具，对齐 Python system_info。 */
    @Bean
    public SystemInfoTool systemInfoTool(SystemResourceService systemResourceService) {
        return new SystemInfoTool(systemResourceService);
    }

    /** 2026-08-15：高级计算器（单位换算），对齐 Python AdvancedCalculatorTool。 */
    @Bean
    public AdvancedCalculatorTool advancedCalculatorTool() {
        return new AdvancedCalculatorTool();
    }

    @Bean
    public AgentOrchestrator agentOrchestrator(LlmProviderRouter llmProviderRouter,
                                               ToolExecutor toolExecutor,
                                               ConversationMemoryService conversationMemoryService,
                                               PromptService promptService,
                                               BranchFailureDetector branchFailureDetector) {
        return new AgentOrchestrator(llmProviderRouter, toolExecutor, conversationMemoryService,
                promptService, branchFailureDetector, AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS);
    }

    /** TODO-110 Task 4.4：分支失败检测 + 铁律违反扫描（模式来自 rules.md + 硬编码清单）。 */
    @Bean
    public BranchFailureDetector branchFailureDetector(SoulLoader soulLoader) {
        String rules = soulLoader.data() == null ? "" : soulLoader.data().rules();
        return new BranchFailureDetector(rules);
    }

    /** TODO-110 Task 3：灵魂层加载（soul/ 目录，SOUL_DIR 可覆盖）。 */
    @Bean
    public SoulLoader soulLoader(@Value("${ai.soul.dir:../../soul}") String soulDir) {
        return new SoulLoader(Path.of(soulDir));
    }

    @Bean
    public SystemPromptBuilder systemPromptBuilder() {
        return new SystemPromptBuilder();
    }

    /** TODO-110 Task 3：提示词编排（soul + persona + 工具指令 + 模型覆盖层）。 */
    @Bean
    public PromptService promptService(SoulLoader soulLoader,
                                       SystemPromptBuilder systemPromptBuilder,
                                       ToolExecutor toolExecutor,
                                       RoleService roleService,
                                       @Value("${ai.llm.text-tool-patterns:dolphin,phi2,orca-mini,orca2}") List<String> textToolPatterns,
                                       @Value("${ai.llm.ollama.model:qwen2.5:7b}") String defaultModel,
                                       @Value("${ai.llm.max-context-tokens:8000}") int maxContextTokens) {
        return new PromptService(soulLoader, systemPromptBuilder, toolExecutor, roleService,
                textToolPatterns, defaultModel, maxContextTokens);
    }

    /** TODO-110 Task 3.4：heart_record 工具（心证铁卷 + 主人铁律管理）。 */
    @Bean
    public HeartRecordTool heartRecordTool(@Value("${ai.soul.dir:../../soul}") String soulDir) {
        return new HeartRecordTool(Path.of(soulDir));
    }

    /** TODO-110 Task 5：真实 embedding（Ollama /api/embed），失败时 n-gram 兜底。*/
    @Bean
    public EmbeddingService embeddingService(
            @Value("${ai.llm.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ai.embedding.model:nomic-embed-text}") String model,
            @Value("${ai.embedding.timeout:10s}") Duration timeout,
            @Value("${ai.embedding.enabled:true}") boolean enabled) {
        return new EmbeddingService(baseUrl, model, timeout, enabled);
    }

    /** TODO-110 Task 5：LLM 提取（记忆蒸馏 / 项目上下文）。*/
    @Bean
    public LlmExtractionService llmExtractionService(
            LlmProviderRouter llmProviderRouter,
            @Value("${ai.llm.ollama.model:qwen2.5:7b}") String defaultModel,
            @Value("${ai.llm.extraction.timeout:30s}") Duration timeout,
            @Value("${ai.llm.extraction.enabled:true}") boolean enabled) {
        return new LlmExtractionService(llmProviderRouter, defaultModel, timeout, enabled);
    }

    /** 本地系统资源监控（java 模式 /api/system/resources）。 */
    @Bean
    public SystemResourceService systemResourceService(
            @Value("${ai.llm.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl) {
        return new SystemResourceService(ollamaBaseUrl);
    }

    /** 敏感字段落盘加密（密钥由 JWT_SECRET 派生）。 */
    @Bean
    public SecretCrypto secretCrypto(@Value("${JWT_SECRET:}") String jwtSecret) {
        return new SecretCrypto(jwtSecret);
    }

    @Bean
    public VectorMemoryRepository vectorMemoryRepository(
            EmbeddingService embeddingService,
            @Value("${intelligent-agent.data-dir:data}") String dataDir) {
        return new VectorMemoryRepository(embeddingService, Path.of(dataDir),
                VectorMemoryRepository.DEFAULT_MAX_RECORDS);
    }

    @Bean
    public SemanticResponseCache semanticResponseCache(EmbeddingService embeddingService) {
        return new SemanticResponseCache(SemanticResponseCache.DEFAULT_TTL, embeddingService);
    }

    @Bean
    public MemoryDistillationService memoryDistillationService(
            LlmExtractionService llmExtractionService,
            @Value("${ai.memory.project-extraction-interval:8}") int projectInterval) {
        return new MemoryDistillationService(
                MemoryDistillationService.DEFAULT_INTERVAL,
                MemoryDistillationService.DEFAULT_SUMMARY_INTERVAL,
                projectInterval, llmExtractionService);
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

package com.intelligent.agent.web.config;

import com.intelligent.agent.web.ai.agent.AgentOrchestrator;
import com.intelligent.agent.web.ai.agent.ActiveChatLimiter;
import com.intelligent.agent.web.ai.agent.BranchFailureDetector;
import com.intelligent.agent.web.ai.agent.planning.LlmTaskPlanner;
import com.intelligent.agent.web.ai.agent.planning.PlanningComplexityDetector;
import com.intelligent.agent.web.ai.agent.planning.TaskPlanner;
import com.intelligent.agent.web.ai.agent.subagent.SubAgentExecutor;
import com.intelligent.agent.web.ai.agent.approval.ApprovalGate;
import com.intelligent.agent.web.ai.agent.reflection.AnswerReflector;
import com.intelligent.agent.web.ai.agent.reflection.LlmAnswerReflector;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.ContextBudget;
import com.intelligent.agent.web.ai.memory.LlmExtractionService;
import com.intelligent.agent.web.ai.memory.MemoryDistillationService;
import com.intelligent.agent.web.ai.memory.SemanticResponseCache;
import com.intelligent.agent.web.ai.prompt.PromptService;
import com.intelligent.agent.web.ai.prompt.SoulLoader;
import com.intelligent.agent.web.ai.prompt.SystemPromptBuilder;
import com.intelligent.agent.web.ai.skill.SkillMatcher;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import com.intelligent.agent.web.infrastructure.vectorstore.EmbeddingService;
import com.intelligent.agent.web.infrastructure.monitoring.SystemResourceService;
import com.intelligent.agent.web.infrastructure.observability.TraceService;
import com.intelligent.agent.web.infrastructure.security.SecretCrypto;
import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.ai.tool.builtin.CalculatorTool;
import com.intelligent.agent.web.ai.tool.builtin.TimeTool;
import com.intelligent.agent.web.ai.tool.builtin.file.FileTool;
import com.intelligent.agent.web.ai.tool.builtin.shell.ShellTool;
import com.intelligent.agent.web.ai.tool.builtin.web.WebSearchTool;
import com.intelligent.agent.web.ai.tool.builtin.web.WebFetchTool;
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
import com.intelligent.agent.web.domain.skill.SkillService;
import com.intelligent.agent.web.domain.task.TaskService;
import com.intelligent.agent.web.im.ChannelAdapterManager;
import com.intelligent.agent.web.integration.feishu.FeishuChannelClient;
import com.intelligent.agent.web.infrastructure.scheduler.TaskSchedulerService;
import com.intelligent.agent.web.service.ImageService;
import com.intelligent.agent.web.service.ConfigRuntimeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

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

    /** 流式对话并发上限（WS + SSE 共用），runtime 配置 stream_concurrency 可调。 */
    @Bean
    public ActiveChatLimiter activeChatLimiter(
            @Value("${ai.chat.max-concurrent-streams:32}") int maxConcurrency) {
        return new ActiveChatLimiter(Math.max(1, maxConcurrency));
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

    /** R-03：网页正文抓取工具（白名单域名 + SSRF 每跳校验；空白名单 = 全部拒绝）。 */
    @Bean
    public WebFetchTool webFetchTool(
            @Value("${ai.web-fetch.allowed-domains:}") List<String> allowedDomains,
            @Value("${ai.web-fetch.timeout-seconds:10}") int timeoutSeconds,
            @Value("${ai.web-fetch.max-body-chars:8000}") int maxBodyChars) {
        return new WebFetchTool(allowedDomains == null ? List.of() : allowedDomains,
                timeoutSeconds, maxBodyChars);
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
                                               BranchFailureDetector branchFailureDetector,
                                               TraceService traceService,
                                               ConfigRuntimeService configRuntimeService,
                                               TaskPlanner taskPlanner,
                                               AnswerReflector answerReflector,
                                               ApprovalGate approvalGate,
                                               SkillMatcher skillMatcher,
                                               ContextBudget contextBudget,
                                               SubAgentExecutor subAgentExecutor,
                                               com.intelligent.agent.web.ai.agent.approval.ApprovalNotifier approvalNotifier) {
        return new AgentOrchestrator(llmProviderRouter, toolExecutor, conversationMemoryService,
                promptService, branchFailureDetector, AgentOrchestrator.DEFAULT_MAX_TOOL_ROUNDS,
                traceService, configRuntimeService, taskPlanner, answerReflector, approvalGate,
                skillMatcher, contextBudget, subAgentExecutor, approvalNotifier);
    }

    /** R-07：子代理/多代理编排执行器（只读研究子代理，并行分组 + 按序合并）。 */
    @Bean
    public SubAgentExecutor subAgentExecutor(LlmProviderRouter llmProviderRouter,
                                             ToolExecutor toolExecutor,
                                             ConversationMemoryService conversationMemoryService,
                                             PromptService promptService,
                                             TraceService traceService,
                                             @Value("${ai.subagent.enabled:true}") boolean enabled,
                                             @Value("${ai.subagent.pool-size:4}") int poolSize,
                                             @Value("${ai.subagent.queue-size:32}") int queueSize,
                                             @Value("${ai.subagent.timeout:120s}") Duration timeout,
                                             @Value("${ai.subagent.max-rounds:3}") int maxRounds,
                                             @Value("${ai.subagent.max-result-chars:2000}") int maxResultChars,
                                             @Value("${ai.subagent.tools:}") List<String> allowedTools) {
        return new SubAgentExecutor(llmProviderRouter, toolExecutor, conversationMemoryService,
                promptService, traceService, enabled, poolSize, queueSize, timeout, maxRounds,
                maxResultChars, allowedTools == null ? List.of() : allowedTools);
    }

    /** R-01：上下文 token 预算（num_ctx 唯一来源）。与 OllamaLlmProvider 共用同一配置表与优先级。 */
    @Bean
    public ContextBudget contextBudget(
            @Value("${ai.llm.ollama.num-ctx:4096}") int defaultNumCtx,
            @Value("#{${ai.llm.ollama.num-ctx-by-model:{}}}") Map<String, Integer> numCtxByModel) {
        return new ContextBudget(defaultNumCtx, numCtxByModel);
    }

    /** 技能运行时匹配/注入（迁移自 Python skills/manager.py + applicator.py）。 */
    @Bean
    public SkillMatcher skillMatcher(SkillService skillService,
                                     LlmProviderRouter llmProviderRouter,
                                     @Value("${ai.skills.runtime-enabled:true}") boolean enabled,
                                     @Value("${ai.skills.llm-timeout:10s}") Duration llmTimeout) {
        return new SkillMatcher(skillService, llmProviderRouter, enabled, llmTimeout);
    }

    /** G6 planning 前置：LLM 计划器（启发式门控 + 低温度计划生成，失败降级）。 */
    @Bean
    public TaskPlanner taskPlanner(LlmProviderRouter llmProviderRouter,
                                   @Value("${ai.planning.enabled:true}") boolean enabled,
                                   @Value("${ai.planning.min-message-length:24}") int minMessageLength,
                                   @Value("${ai.planning.max-steps:6}") int maxSteps,
                                   @Value("${ai.planning.timeout:30s}") Duration timeout) {
        return new LlmTaskPlanner(llmProviderRouter,
                new PlanningComplexityDetector(minMessageLength), enabled, timeout, maxSteps);
    }

    /** G6 reflection 后验：答案自检器（工具执行后修订草稿，失败保留草稿）。 */
    @Bean
    public AnswerReflector answerReflector(LlmProviderRouter llmProviderRouter,
                                           @Value("${ai.reflection.enabled:true}") boolean enabled,
                                           @Value("${ai.reflection.timeout:30s}") Duration timeout) {
        return new LlmAnswerReflector(llmProviderRouter, enabled, timeout);
    }

    /** G6 HITL：不可逆工具调用审批门（web/WS 渠道交互，IM 直发；拒绝/超时默认安全）。 */
    @Bean
    public ApprovalGate approvalGate(@Value("${ai.hitl.enabled:true}") boolean enabled,
                                     @Value("${ai.hitl.timeout:120s}") Duration timeout) {
        return new ApprovalGate(enabled, timeout);
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
                                       @Value("${ai.llm.max-context-tokens:8000}") int maxContextTokens,
                                       ContextBudget contextBudget) {
        return new PromptService(soulLoader, systemPromptBuilder, toolExecutor, roleService,
                textToolPatterns, defaultModel, maxContextTokens, contextBudget);
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

    /** R-11：敏感字段落盘加密（独立密钥文件 data/keys/key.<id>.key + keyId 版本化密文；
     *  JWT_SECRET 仅用于兼容旧格式密文解密）。 */
    @Bean
    public SecretCrypto secretCrypto(@Value("${JWT_SECRET:}") String jwtSecret,
                                     @Value("${intelligent-agent.data-dir:data}") String dataDir) {
        return new SecretCrypto(jwtSecret, Path.of(dataDir).resolve("keys"));
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
            MemoryDistillationService memoryDistillationService,
            @Qualifier("memoryExecutor") ExecutorService memoryExecutor) {
        return new ConversationMemoryService(
                vectorMemoryRepository, semanticResponseCache, memoryDistillationService,
                memoryExecutor);
    }
}

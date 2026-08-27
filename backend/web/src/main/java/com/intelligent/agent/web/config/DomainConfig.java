package com.intelligent.agent.web.config;

import com.intelligent.agent.web.domain.role.RoleService;
import com.intelligent.agent.web.domain.conversation.ConversationService;
import com.intelligent.agent.web.domain.project.ProjectService;
import com.intelligent.agent.web.domain.task.TaskService;
import com.intelligent.agent.web.domain.knowledge.KnowledgeService;
import com.intelligent.agent.web.domain.skill.SkillService;
import com.intelligent.agent.web.domain.analytics.AnalyticsService;
import com.intelligent.agent.web.domain.analytics.AnalyticsService.CostConfig;
import com.intelligent.agent.web.domain.teaching.TeachingService;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import com.intelligent.agent.web.infrastructure.observability.TraceService;
import com.intelligent.agent.web.infrastructure.observability.OtlpTraceExporter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * 领域服务装配。数据目录默认 backend/web/data，
 * 生产可用 INTELLIGENT_AGENT_DATA_DIR 覆盖（Java 侧持久化源头，与 Python 数据分离，
 * Plan 3 迁移阶段负责导入既有 JSON 状态）。
 */
@Configuration
public class DomainConfig {

    @Value("${intelligent-agent.data-dir:data}")
    private String dataDir;

    @Value("${ai.trace.export.enabled:false}")
    private boolean traceExportEnabled;

    @Value("${ai.trace.export.endpoint:http://localhost:4318}")
    private String traceExportEndpoint;

    @Value("${ai.trace.export.timeout-ms:5000}")
    private long traceExportTimeoutMs;

    @Value("${ai.cost.enabled:true}")
    private boolean costEnabled;

    @Value("${ai.cost.monthly-limit-cny:0}")
    private double costMonthlyLimitCny;

    @SuppressWarnings("unchecked")
    @Value("#{${ai.cost.per-1m-tokens:{}}}")
    private Map<String, Map<String, Double>> costPer1mTokens;

    @Bean
    public RoleService roleService() {
        return new RoleService(Path.of(dataDir));
    }

    @Bean
    public ConversationService conversationService() {
        return new ConversationService(Path.of(dataDir));
    }

    @Bean
    public ProjectService projectService(VectorMemoryRepository vectorMemoryRepository) {
        return new ProjectService(Path.of(dataDir), vectorMemoryRepository);
    }

    @Bean
    public TaskService taskService() {
        return new TaskService(Path.of(dataDir));
    }

    @Bean
    public KnowledgeService knowledgeService(VectorMemoryRepository vectorMemoryRepository) {
        return new KnowledgeService(Path.of(dataDir), vectorMemoryRepository);
    }

    @Bean
    public SkillService skillService() {
        return new SkillService(Path.of(dataDir));
    }

    @Bean
    public AnalyticsService analyticsService() {
        return new AnalyticsService(Path.of(dataDir),
                new CostConfig(costEnabled, costMonthlyLimitCny, costPer1mTokens));
    }

    @Bean
    public TeachingService teachingService() {
        return new TeachingService(Path.of(dataDir));
    }

    /** G4：Agent 运行追踪（data/traces/ 落盘，默认 500 条上限；可选 OTLP 导出）。 */
    @Bean
    public TraceService traceService(AnalyticsService analyticsService) {
        OtlpTraceExporter exporter = new OtlpTraceExporter(
                traceExportEnabled, traceExportEndpoint,
                Duration.ofMillis(Math.max(100, traceExportTimeoutMs)));
        return new TraceService(Path.of(dataDir), TraceService.DEFAULT_MAX_TRACES, exporter,
                analyticsService::recordFromTrace);
    }
}

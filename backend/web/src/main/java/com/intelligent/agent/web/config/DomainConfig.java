package com.intelligent.agent.web.config;

import com.intelligent.agent.web.domain.role.RoleService;
import com.intelligent.agent.web.domain.conversation.ConversationService;
import com.intelligent.agent.web.domain.project.ProjectService;
import com.intelligent.agent.web.domain.task.TaskService;
import com.intelligent.agent.web.domain.knowledge.KnowledgeService;
import com.intelligent.agent.web.domain.skill.SkillService;
import com.intelligent.agent.web.domain.analytics.AnalyticsService;
import com.intelligent.agent.web.domain.teaching.TeachingService;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 领域服务装配。数据目录默认 backend/web/data，
 * 生产可用 INTELLIGENT_AGENT_DATA_DIR 覆盖（Java 侧持久化源头，与 Python 数据分离，
 * Plan 3 迁移阶段负责导入既有 JSON 状态）。
 */
@Configuration
public class DomainConfig {

    @Value("${intelligent-agent.data-dir:data}")
    private String dataDir;

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
        return new AnalyticsService(Path.of(dataDir));
    }

    @Bean
    public TeachingService teachingService() {
        return new TeachingService(Path.of(dataDir));
    }
}

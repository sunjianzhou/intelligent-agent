package com.intelligent.agent.web.config;

import com.intelligent.agent.web.domain.role.RoleService;
import com.intelligent.agent.web.domain.conversation.ConversationService;
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
}

package com.intelligent.agent.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.domain.task.TaskService;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.feishu.FeishuMessageSender;
import com.intelligent.agent.web.im.RetryConfig;
import com.intelligent.agent.web.infrastructure.scheduler.TaskSchedulerService;
import com.intelligent.agent.web.integration.ChannelClient;
import com.intelligent.agent.web.integration.ChannelRouter;
import com.intelligent.agent.web.integration.comfyui.ComfyUiClient;
import com.intelligent.agent.web.integration.feishu.FeishuChannelClient;
import com.intelligent.agent.web.integration.mcp.McpToolRegistry;
import com.intelligent.agent.web.integration.mcp.McpConnectionManager;
import com.intelligent.agent.web.integration.telegram.TelegramChannelClient;
import com.intelligent.agent.web.integration.wechat.WeChatChannelClient;
import com.intelligent.agent.web.infrastructure.security.SecretCrypto;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.wecom.WeComMessageSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 具名集成装配（Plan 2 / Task 5）：
 * 飞书 / 企微 / Telegram 通道客户端 + ComfyUI + MCP 注册表 + 通道路由 +
 * 任务调度服务。
 */
@Configuration
public class IntegrationConfig {

    @Value("${intelligent-agent.data-dir:data}")
    private String dataDir;

    @Value("${feishu.enabled:false}")
    private boolean feishuEnabled;

    @Value("${wecom.enabled:false}")
    private boolean wecomEnabled;

    @Value("${telegram.enabled:false}")
    private boolean telegramEnabled;

    @Value("${telegram.bot-token:}")
    private String telegramBotToken;

    @Value("${comfyui.enabled:false}")
    private boolean comfyuiEnabled;

    @Value("${comfyui.base-url:http://localhost:8188}")
    private String comfyuiBaseUrl;

    @Bean
    public FeishuChannelClient feishuChannelClient(FeishuMessageSender feishuMessageSender,
                                                   SecretCrypto secretCrypto) {
        return new FeishuChannelClient(feishuMessageSender, Path.of(dataDir),
                feishuEnabled, RetryConfig.DEFAULT, secretCrypto);
    }

    @Bean
    public WeChatChannelClient weChatChannelClient(WeComMessageSender weComMessageSender) {
        return new WeChatChannelClient(weComMessageSender, wecomEnabled);
    }

    @Bean
    public TelegramChannelClient telegramChannelClient() {
        return new TelegramChannelClient(telegramBotToken, telegramEnabled);
    }

    @Bean
    public ComfyUiClient comfyUiClient(ObjectMapper objectMapper) {
        return new ComfyUiClient(comfyuiBaseUrl, comfyuiEnabled, objectMapper, true);
    }

    @Bean
    public McpToolRegistry mcpToolRegistry() {
        return new McpToolRegistry();
    }

    /** G2：MCP 连接管理器（服务器 CRUD + 动态工具注册 + 启动自动连接）。 */
    @Bean
    public McpConnectionManager mcpConnectionManager(SecretCrypto secretCrypto,
                                                     ToolExecutor toolExecutor) {
        return new McpConnectionManager(Path.of(dataDir), secretCrypto, toolExecutor);
    }

    @Bean
    public ChannelRouter channelRouter(List<ChannelClient> channelClients) {
        return new ChannelRouter(channelClients, Duration.ofMinutes(5));
    }

    @Bean
    public TaskSchedulerService taskSchedulerService(TaskService taskService,
                                                     TaskScheduler taskScheduler,
                                                     LlmProviderRouter llmProviderRouter,
                                                     ObjectProvider<ToolExecutor> toolExecutorProvider) {
        return new TaskSchedulerService(
                taskService, Path.of(dataDir), taskScheduler, llmProviderRouter, toolExecutorProvider);
    }
}

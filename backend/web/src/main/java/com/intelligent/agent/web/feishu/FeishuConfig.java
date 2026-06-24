package com.intelligent.agent.web.feishu;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@ConfigurationProperties(prefix = "feishu")
public class FeishuConfig {

    private boolean enabled = false;
    private String appId = "";
    private String appSecret = "";
    private String encryptKey = "";
    private String verificationToken = "";
    private String wsEndpoint = "wss://open.feishu.cn/event/v2/websocket/connect";
    private int reconnectDelaySeconds = 5;
    private int reconnectMaxDelaySeconds = 300;

    /** 机器人自身的 open_id，用于在群聊 mentions 列表中精确判断"是否 @ 了机器人"。
     *  留空时退化为"群里只要有人被 @ 就当作可能 @ 了机器人"的低精度启发式。
     *  飞书开放平台「凭证与基础信息」页可查看应用的 open_id。*/
    private String botOpenId = "";

    /** 5 线程 + 有界队列 100 + CallerRunsPolicy，与主 streamExecutor 完全隔离 */
    @Bean(name = "feishuStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService feishuStreamExecutor() {
        final AtomicInteger count = new AtomicInteger(1);
        return new ThreadPoolExecutor(
                5, 5,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "feishu-worker-" + count.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    // getters & setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
    public String getEncryptKey() { return encryptKey; }
    public void setEncryptKey(String encryptKey) { this.encryptKey = encryptKey; }
    public String getVerificationToken() { return verificationToken; }
    public void setVerificationToken(String verificationToken) { this.verificationToken = verificationToken; }
    public String getWsEndpoint() { return wsEndpoint; }
    public void setWsEndpoint(String wsEndpoint) { this.wsEndpoint = wsEndpoint; }
    public int getReconnectDelaySeconds() { return reconnectDelaySeconds; }
    public void setReconnectDelaySeconds(int reconnectDelaySeconds) { this.reconnectDelaySeconds = reconnectDelaySeconds; }
    public int getReconnectMaxDelaySeconds() { return reconnectMaxDelaySeconds; }
    public void setReconnectMaxDelaySeconds(int reconnectMaxDelaySeconds) { this.reconnectMaxDelaySeconds = reconnectMaxDelaySeconds; }
    public String getBotOpenId() { return botOpenId; }
    public void setBotOpenId(String botOpenId) { this.botOpenId = botOpenId; }
}

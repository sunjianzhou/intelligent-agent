package com.intelligent.agent.web.wecom;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@ConfigurationProperties(prefix = "wecom")
public class WeComConfig {

    private boolean enabled = false;
    private String corpId = "";
    private int agentId = 0;
    private String secret = "";
    private String token = "";
    private String aesKey = "";

    /** 3 线程 + 有界队列 50，与飞书 executor 完全隔离 */
    @Bean(name = "wecomExecutor", destroyMethod = "shutdown")
    public ExecutorService wecomExecutor() {
        final AtomicInteger count = new AtomicInteger(1);
        return new ThreadPoolExecutor(
                3, 3,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "wecom-worker-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCorpId() { return corpId; }
    public void setCorpId(String corpId) { this.corpId = corpId; }
    public int getAgentId() { return agentId; }
    public void setAgentId(int agentId) { this.agentId = agentId; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getAesKey() { return aesKey; }
    public void setAesKey(String aesKey) { this.aesKey = aesKey; }
}

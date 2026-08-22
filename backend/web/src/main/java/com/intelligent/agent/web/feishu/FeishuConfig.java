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

    /** 机器人自身的 open_id，用于在群聊 mentions 列表中精确判断"是否 @ 了机器人"。
     *  留空时退化为"群里只要有人被 @ 就当作可能 @ 了机器人"的低精度启发式。
     *  飞书开放平台「凭证与基础信息」页可查看应用的 open_id。*/
    private String botOpenId = "";

    /** 群聊表情回应总开关（TODO-81 遗留补齐）：
     *  开启后，群聊收到纯表情消息会回点同一表情（不再送 LLM），
     *  模型判定 NO_REPLY 时用 👍 表情轻量回应代替纯静默。*/
    private boolean emojiReactionEnabled = true;

    /** 5 线程 + 有界队列 100 + AbortPolicy：
     *  队列满时抛 RejectedExecutionException，由事件入口兜底回复"服务繁忙"；
     *  绝不让长任务在 WS/回调事件线程上执行（此前 CallerRunsPolicy 会把整个飞书通道读循环卡死）。 */
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
                new ThreadPoolExecutor.AbortPolicy()
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
    public String getBotOpenId() { return botOpenId; }
    public void setBotOpenId(String botOpenId) { this.botOpenId = botOpenId; }
    public boolean isEmojiReactionEnabled() { return emojiReactionEnabled; }
    public void setEmojiReactionEnabled(boolean emojiReactionEnabled) { this.emojiReactionEnabled = emojiReactionEnabled; }
}

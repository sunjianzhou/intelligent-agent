package com.intelligent.agent.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class FeishuWebSocketClient implements SmartLifecycle {

    private final FeishuConfig config;
    private final FeishuEventController eventController;
    private final ExecutorService executor;
    private final String feishuBase;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean running     = new AtomicBoolean(false);
    private final AtomicInteger currentDelay;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "feishu-reconnect");
                    t.setDaemon(true);
                    return t;
                }
            });

    private volatile String appAccessToken;
    private volatile long   appTokenExpiryMs = 0;
    private final ReentrantLock tokenLock = new ReentrantLock();

    private volatile WebSocketClient wsClient;

    @Autowired
    public FeishuWebSocketClient(FeishuConfig config,
                                  FeishuEventController eventController,
                                  @Qualifier("feishuStreamExecutor") ExecutorService executor) {
        this(config, eventController, executor, "https://open.feishu.cn");
    }

    FeishuWebSocketClient(FeishuConfig config, FeishuEventController eventController,
                           ExecutorService executor, String feishuBase) {
        this.config          = config;
        this.eventController = eventController;
        this.executor        = executor;
        this.feishuBase      = feishuBase;
        this.currentDelay    = new AtomicInteger(config.getReconnectDelaySeconds());
    }

    @Override
    public boolean isAutoStartup() { return config.isEnabled(); }

    @Override
    public void start() {
        if (!config.isEnabled()) return;
        validateCredentials();   // 凭据检查在网络操作之前，空则抛 IllegalStateException
        running.set(true);
        log.info("飞书 WS 客户端启动");
        refreshAppAccessToken();
        connect();
    }

    @Override
    public void stop() {
        running.set(false);
        WebSocketClient ws = wsClient;
        if (ws != null) {
            try { ws.closeBlocking(); } catch (Exception e) {
                log.warn("关闭飞书 WS 失败: {}", e.getMessage());
            }
        }
        log.info("飞书 WS 客户端已停止");
    }

    @Override
    public boolean isRunning() { return running.get(); }

    private void validateCredentials() {
        if (config.getAppId() == null || config.getAppId().trim().isEmpty()) {
            throw new IllegalStateException("feishu.appId 未配置，无法启动飞书 WS");
        }
        if (config.getAppSecret() == null || config.getAppSecret().trim().isEmpty()) {
            throw new IllegalStateException("feishu.appSecret 未配置，无法启动飞书 WS");
        }
    }

    private void ensureTokenValid() {
        if (System.currentTimeMillis() < appTokenExpiryMs - 300_000L) return;
        refreshAppAccessToken();
    }

    private void refreshAppAccessToken() {
        tokenLock.lock();
        try {
            if (System.currentTimeMillis() < appTokenExpiryMs - 300_000L) return;
            String url = feishuBase + "/open-apis/auth/v3/app_access_token/internal";
            Map<String, String> body = new HashMap<String, String>();
            body.put("app_id",     config.getAppId());
            body.put("app_secret", config.getAppSecret());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAcceptCharset(Collections.singletonList(StandardCharsets.UTF_8));

            RestTemplate rt = new RestTemplate();
            ResponseEntity<String> res = rt.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            if (res.getStatusCode().is2xxSuccessful()) {
                Map<?, ?> json   = objectMapper.readValue(res.getBody(), Map.class);
                appAccessToken   = (String)  json.get("app_access_token");
                int expire       = (Integer) json.get("expire");
                appTokenExpiryMs = System.currentTimeMillis() + (expire - 300L) * 1000L;
                log.info("飞书 app_access_token 刷新成功");
            }
        } catch (Exception e) {
            log.error("刷新 app_access_token 失败", e);
        } finally {
            tokenLock.unlock();
        }
    }

    private void connect() {
        try {
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Authorization", "Bearer " + appAccessToken);

            URI uri = new URI(config.getWsEndpoint());
            wsClient = new WebSocketClient(uri, headers) {
                @Override
                public void onOpen(ServerHandshake h) {
                    log.info("飞书 WS 已连接");
                    currentDelay.set(config.getReconnectDelaySeconds());
                }

                @Override
                public void onMessage(String raw) {
                    handleFrame(raw);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.warn("飞书 WS 断线 code={}, reason={}", code, reason);
                    if (running.get()) scheduleReconnect();
                }

                @Override
                public void onError(Exception ex) {
                    log.error("飞书 WS 错误", ex);
                }
            };
            wsClient.connect();
        } catch (Exception e) {
            log.error("飞书 WS 连接失败", e);
            if (running.get()) scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        int delay = currentDelay.get();
        currentDelay.set(nextDelay(delay));
        log.info("飞书 WS 将在 {}s 后重连", delay);
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                if (!running.get()) return;
                ensureTokenValid();
                connect();
            }
        }, delay, TimeUnit.SECONDS);
    }

    int nextDelay(int current) {
        int next = current * 2;
        return Math.min(next, config.getReconnectMaxDelaySeconds());
    }

    private void handleFrame(String raw) {
        try {
            Map<?, ?> frame  = objectMapper.readValue(raw, Map.class);
            Object typeObj   = frame.get("type");
            int    frameType = typeObj instanceof Integer ? (Integer) typeObj : -1;

            if (frameType == 2 || frameType == 14) {
                Map<String, Object> pong = new HashMap<String, Object>();
                pong.put("type",    frameType);
                pong.put("service", 0);
                pong.put("body",    "pong");
                WebSocketClient ws = wsClient;
                if (ws != null && ws.isOpen()) {
                    ws.send(objectMapper.writeValueAsString(pong));
                }
                return;
            }

            String bodyStr = (String) frame.get("body");
            if (bodyStr == null || bodyStr.isEmpty()) return;

            String encryptKey = config.getEncryptKey();
            if (encryptKey != null && !encryptKey.trim().isEmpty()) {
                try {
                    bodyStr = FeishuCrypto.decrypt(bodyStr, encryptKey);
                } catch (Exception e) {
                    log.error("飞书 WS payload 解密失败（协议层异常），关闭连接触发重连", e);
                    WebSocketClient ws = wsClient;
                    if (ws != null) ws.close();
                    return;
                }
            }

            try {
                eventController.routeEvent(bodyStr);
            } catch (Exception e) {
                log.warn("飞书事件路由失败（数据层异常，跳过本条）: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("handleFrame 解析失败: {}", e.getMessage());
        }
    }
}

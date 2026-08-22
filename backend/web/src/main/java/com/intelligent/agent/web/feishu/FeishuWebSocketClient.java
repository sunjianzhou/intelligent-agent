package com.intelligent.agent.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 飞书长连接客户端：基于官方 oapi-sdk 的 {@link Client}（端点发现 + pbbp2 二进制帧 + 自动重连/心跳）。
 * 早期版本手写直连静态 wss 地址，飞书长连接实际需要先用 app_access_token 换取临时连接地址再走二进制协议，
 * 手写版无法通过握手（见 TODO-84 排障记录），故改为官方 SDK，仅保留业务路由层（{@link FeishuEventController}）。
 */
@Slf4j
@Component
public class FeishuWebSocketClient implements SmartLifecycle {

    private final FeishuConfig config;
    private final FeishuEventController eventController;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Client client;

    @Autowired
    public FeishuWebSocketClient(FeishuConfig config,
                                  FeishuEventController eventController,
                                  @Qualifier("feishuStreamExecutor") ExecutorService executor) {
        this.config          = config;
        this.eventController = eventController;
        this.executor         = executor;
    }

    @Override
    public boolean isAutoStartup() { return config.isEnabled(); }

    @Override
    public void start() {
        if (!config.isEnabled()) return;
        validateCredentials();
        running.set(true);
        log.info("飞书 WS 客户端启动");

        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        forwardToEventController(event);
                    }
                })
                .build();

        client = new Client.Builder(config.getAppId(), config.getAppSecret())
                .eventHandler(dispatcher)
                .build();

        // Client.start() 在连接失败时会同步重试（默认间隔 120s），放executor 异步执行，避免阻塞应用启动
        try {
            executor.submit(() -> {
                try {
                    client.start();
                    log.info("飞书 WS 已连接");
                } catch (Exception e) {
                    log.error("飞书 WS 连接失败", e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.error("飞书 WS 启动任务被拒绝（事件线程池已满），连接未建立", e);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        // oapi-sdk 2.4.19（Maven Central 当前最新发布版）未暴露公开的 close()/stop() API，
        // 无法主动断开底层连接；进程退出时由容器 SIGKILL 兜底回收，不影响功能正确性。
        log.info("飞书 WS 客户端已停止（标记位，底层连接随进程退出回收）");
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

    /** 将官方 SDK 的类型化事件还原为业务层一直消费的 {header, event} JSON 信封，复用既有 routeEvent 逻辑不变。*/
    private void forwardToEventController(P2MessageReceiveV1 event) {
        try {
            Map<String, Object> header = new HashMap<>();
            header.put("event_type", "im.message.receive_v1");
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("header", header);
            envelope.put("event", objectMapper.readValue(Jsons.DEFAULT.toJson(event.getEvent()), Map.class));
            eventController.routeEvent(objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.error("飞书事件转换失败（跳过本条，不影响 WS 连接）: {}", e.getMessage());
        }
    }
}

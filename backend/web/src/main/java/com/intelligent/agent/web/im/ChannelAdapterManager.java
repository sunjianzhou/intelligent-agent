package com.intelligent.agent.web.im;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * Channel 适配器管理器（Java 侧）。
 *
 * 聚合所有 ChannelAdapter 实现，提供：
 *   - 按 channel 类型获取 adapter
 *   - 多通道并行发送（失败隔离）
 *   - 生命周期统一管理
 */
@Slf4j
@Component
public class ChannelAdapterManager {

    private final Map<ChannelType, ChannelAdapter> adapters = new ConcurrentHashMap<>();
    private final ExecutorService broadcastExecutor = Executors.newFixedThreadPool(4);

    /** Spring 自动注入所有 ChannelAdapter Bean */
    public ChannelAdapterManager(List<ChannelAdapter> adapterList) {
        for (ChannelAdapter a : adapterList) {
            if (a.isEnabled()) {
                adapters.put(a.channelType(), a);
                log.info("[ChannelManager] 注册 channel: {}", a.channelType().getValue());
            }
        }
    }

    public Optional<ChannelAdapter> get(ChannelType type) {
        return Optional.ofNullable(adapters.get(type));
    }

    public List<ChannelAdapter> listEnabled() {
        List<ChannelAdapter> result = new ArrayList<>();
        for (ChannelAdapter a : adapters.values()) {
            if (a.isEnabled()) result.add(a);
        }
        return result;
    }

    /** 多通道并行发送（失败隔离） */
    public Map<ChannelType, SendResult> broadcastText(
            String text, Map<ChannelType, String> receivers, String chatType) {

        Map<ChannelType, SendResult> results = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        for (Map.Entry<ChannelType, String> entry : receivers.entrySet()) {
            ChannelType ch = entry.getKey();
            String receiverId = entry.getValue();
            ChannelAdapter adapter = adapters.get(ch);
            if (adapter == null || !adapter.isEnabled()) continue;

            futures.add(broadcastExecutor.submit(() -> {
                try {
                    SendResult r = adapter.sendText(receiverId, text, chatType);
                    results.put(ch, r);
                } catch (Exception e) {
                    log.error("[ChannelManager] broadcast {} 失败: {}",
                            ch.getValue(), e.getMessage());
                    results.put(ch, new SendResult(false, null, e.getMessage(), ch, 0));
                }
            }));
        }

        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[ChannelManager] broadcast 超时: {}", e.getMessage());
            }
        }

        return results;
    }

    /** 生命周期：启动所有 adapter */
    public void startAll() {
        for (ChannelAdapter a : listEnabled()) {
            try {
                a.start();
            } catch (Exception e) {
                log.error("启动 {} 失败: {}", a.channelType().getValue(), e.getMessage());
            }
        }
    }

    /** 生命周期：停止所有 adapter */
    public void stopAll() {
        for (ChannelAdapter a : listEnabled()) {
            try {
                a.stop();
            } catch (Exception e) {
                log.error("停止 {} 失败: {}", a.channelType().getValue(), e.getMessage());
            }
        }
    }
}

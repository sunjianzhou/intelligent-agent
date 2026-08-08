package com.intelligent.agent.web.integration;

import com.intelligent.agent.web.im.ChannelMessage;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通道路由 + 幂等广播（Plan 2 / Task 5）。
 * <p>
 * 去重规则：以 dedupKey（无则 messageId，再退化为 content 哈希）为键，
 * 在 TTL 窗口内重复广播直接重放最近一次投递结果，保证每条通道每个消息
 * 最多实际投递一次（消息送达与写工具绝不重复执行）。
 */
@Slf4j
public class ChannelRouter {

    private final Map<String, ChannelClient> clients;
    private final Map<String, CachedDelivery> recentDeliveries = new ConcurrentHashMap<>();
    private final Duration dedupTtl;

    public ChannelRouter(List<ChannelClient> channelClients, Duration dedupTtl) {
        this.clients = new HashMap<>();
        for (ChannelClient client : channelClients) {
            clients.put(client.channelType(), client);
        }
        this.dedupTtl = dedupTtl;
    }

    public List<ChannelClient> clients() {
        return List.copyOf(clients.values());
    }

    public BroadcastResult broadcast(ChannelMessage message) {
        String dedupKey = dedupKey(message);
        evictExpired();
        if (dedupKey != null && recentDeliveries.containsKey(dedupKey)) {
            return new BroadcastResult(
                    List.of(recentDeliveries.get(dedupKey).result()), true);
        }

        List<ChannelClient> targets = resolveTargets(message);
        List<DeliveryResult> deliveries = new ArrayList<>();
        for (ChannelClient client : targets) {
            DeliveryResult result;
            try {
                result = client.send(message);
            } catch (Exception e) {
                log.error("通道 {} 投递异常: {}", client.channelType(), e.getMessage());
                result = DeliveryResult.failed(client.channelType(), e.getMessage());
            }
            deliveries.add(result);
        }
        if (dedupKey != null && !deliveries.isEmpty()) {
            recentDeliveries.put(dedupKey, new CachedDelivery(Instant.now(), deliveries.get(0)));
        }
        return new BroadcastResult(deliveries, false);
    }

    /** 按消息 channel 字段解析目标通道；未指定时广播到所有启用通道。 */
    private List<ChannelClient> resolveTargets(ChannelMessage message) {
        List<ChannelClient> targets = new ArrayList<>();
        if (message.getChannel() != null) {
            ChannelClient client = clients.get(message.getChannel().getValue());
            if (client != null && client.isEnabled()) {
                targets.add(client);
            }
            return targets;
        }
        for (ChannelClient client : clients.values()) {
            if (client.isEnabled()) {
                targets.add(client);
            }
        }
        return targets;
    }

    private static String dedupKey(ChannelMessage message) {
        if (message.getDedupKey() != null && !message.getDedupKey().isBlank()) {
            return message.getDedupKey();
        }
        if (message.getMessageId() != null && !message.getMessageId().isBlank()) {
            return message.getMessageId();
        }
        String content = message.getContent() == null ? "" : message.getContent();
        return content.isBlank() ? null : Integer.toHexString(content.hashCode());
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(dedupTtl);
        recentDeliveries.entrySet().removeIf(e -> e.getValue().deliveredAt().isBefore(cutoff));
    }

    private record CachedDelivery(Instant deliveredAt, DeliveryResult result) {
    }
}

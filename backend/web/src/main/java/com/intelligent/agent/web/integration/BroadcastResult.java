package com.intelligent.agent.web.integration;

import java.util.List;

/**
 * 广播结果：每条通道一个投递结果；deduplicated=true 表示命中去重缓存
 * （重放了最近一次成功结果，未实际重复发送）。
 */
public record BroadcastResult(List<DeliveryResult> deliveries, boolean deduplicated) {
}

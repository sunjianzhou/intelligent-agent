package com.intelligent.agent.client.http;

import java.util.List;

/**
 * 消息撤回结果（Plan 3 / Task 3）。
 */
public record RetractResult(boolean success, int requested, int deleted,
                            List<String> deletedIds) {
}

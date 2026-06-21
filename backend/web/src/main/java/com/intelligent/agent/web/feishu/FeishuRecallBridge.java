package com.intelligent.agent.web.feishu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 维护内部 assistant_message_id → 飞书 message_id 的映射，支持撤回时联动调用
 * 飞书官方撤回 API。纯内存态，不落盘，重启即丢（与短期记忆一样是易失态，可接受）。
 */
@Slf4j
@Component
public class FeishuRecallBridge {

    private static final int MAX_ENTRIES = 500;

    private final FeishuMessageSender sender;
    private final Map<String, String> idMapping =
            new LinkedHashMap<String, String>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    @Autowired
    public FeishuRecallBridge(FeishuMessageSender sender) {
        this.sender = sender;
    }

    /** 飞书消息发送成功后调用，记录内部 id → 飞书 id 的映射。 */
    public synchronized void register(String assistantMessageId, String feishuMessageId) {
        if (assistantMessageId == null || feishuMessageId == null) return;
        idMapping.put(assistantMessageId, feishuMessageId);
    }

    /** retract 响应里携带的 deleted_ids 命中映射表的，逐个调用飞书官方撤回 API。 */
    public void onMessagesRetracted(Map<String, Object> retractResponse) {
        if (retractResponse == null) return;
        Object deletedIdsObj = retractResponse.get("deleted_ids");
        if (!(deletedIdsObj instanceof Iterable)) return;

        for (Object idObj : (Iterable<?>) deletedIdsObj) {
            String ourId = String.valueOf(idObj);
            String feishuMessageId;
            synchronized (this) {
                feishuMessageId = idMapping.remove(ourId);
            }
            if (feishuMessageId == null) continue;
            try {
                sender.recall(feishuMessageId);
            } catch (Exception e) {
                log.warn("飞书消息撤回失败（不影响内部存储已完成的删除），feishuMessageId={}: {}",
                        feishuMessageId, e.getMessage());
            }
        }
    }
}

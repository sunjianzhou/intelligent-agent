package com.intelligent.agent.web.feishu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;

class FeishuRecallBridgeTest {

    @Mock FeishuMessageSender sender;
    private FeishuRecallBridge bridge;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        bridge = new FeishuRecallBridge(sender);
    }

    @Test
    void onMessagesRetracted_callsRecall_forMappedIds() throws Exception {
        bridge.register("aid-1", "om_feishu1");

        Map<String, Object> retractResponse = new HashMap<>();
        retractResponse.put("deleted_ids", Collections.singletonList("aid-1"));

        bridge.onMessagesRetracted(retractResponse);

        verify(sender).recall("om_feishu1");
    }

    @Test
    void onMessagesRetracted_skipsUnmappedIds() throws Exception {
        Map<String, Object> retractResponse = new HashMap<>();
        retractResponse.put("deleted_ids", Collections.singletonList("aid-not-registered"));

        bridge.onMessagesRetracted(retractResponse);

        verify(sender, never()).recall(any());
    }

    @Test
    void onMessagesRetracted_recallThrows_doesNotPropagate() throws Exception {
        bridge.register("aid-1", "om_feishu1");
        doThrow(new RuntimeException("撤回超时")).when(sender).recall("om_feishu1");

        Map<String, Object> retractResponse = new HashMap<>();
        retractResponse.put("deleted_ids", Collections.singletonList("aid-1"));

        bridge.onMessagesRetracted(retractResponse);  // 不应抛出

        verify(sender).recall("om_feishu1");
    }

    @Test
    void onMessagesRetracted_nullResponse_doesNothing() {
        bridge.onMessagesRetracted(null);
        verifyNoInteractions(sender);
    }

    @Test
    void register_ignoresNullIds() throws Exception {
        bridge.register(null, "om_x");
        bridge.register("aid-1", null);

        Map<String, Object> retractResponse = new HashMap<>();
        retractResponse.put("deleted_ids", Collections.singletonList("aid-1"));
        bridge.onMessagesRetracted(retractResponse);

        verify(sender, never()).recall(any());
    }

    @Test
    void mapping_evictsOldestEntry_whenOverCapacity() throws Exception {
        for (int i = 0; i < 501; i++) {
            bridge.register("aid-" + i, "om-" + i);
        }
        // 最早插入的 aid-0 应已被淘汰
        Map<String, Object> retractResponse = new HashMap<>();
        retractResponse.put("deleted_ids", Collections.singletonList("aid-0"));
        bridge.onMessagesRetracted(retractResponse);
        verify(sender, never()).recall("om-0");

        // 最近插入的 aid-500 应仍在
        Map<String, Object> retractResponse2 = new HashMap<>();
        retractResponse2.put("deleted_ids", Collections.singletonList("aid-500"));
        bridge.onMessagesRetracted(retractResponse2);
        verify(sender).recall("om-500");
    }
}

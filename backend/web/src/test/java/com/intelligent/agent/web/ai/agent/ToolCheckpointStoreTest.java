package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.tool.ToolCall;
import com.intelligent.agent.web.ai.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** R-16：工具轮结果断点缓存——命中/TTL/清理/签名规范化/容量。 */
class ToolCheckpointStoreTest {

    private static ToolResult ok() {
        return new ToolResult(ToolResult.SUCCESS, Map.of("total", 42), null, 3);
    }

    @Test
    void putAndGetReturnsSameResult() {
        ToolCheckpointStore store = new ToolCheckpointStore();
        ToolResult result = ok();

        store.put("req-1", "counter:{v:1}", result);

        assertThat(store.get("req-1", "counter:{v:1}")).contains(result);
        assertThat(store.get("req-2", "counter:{v:1}")).isEmpty();
        assertThat(store.get("req-1", "counter:{v:2}")).isEmpty();
    }

    @Test
    void expiredEntryIsIgnored() {
        AtomicLong clock = new AtomicLong(1_000);
        ToolCheckpointStore store = new ToolCheckpointStore(true, 10_000, 100, clock::get);
        store.put("req-1", "sig", ok());

        clock.set(11_000); // 10s TTL 已过

        assertThat(store.get("req-1", "sig")).isEmpty();
    }

    @Test
    void removeClearsAllEntriesForRequestId() {
        ToolCheckpointStore store = new ToolCheckpointStore();
        store.put("req-1", "sig-a", ok());
        store.put("req-1", "sig-b", ok());
        store.put("req-2", "sig-a", ok());

        store.remove("req-1");

        assertThat(store.get("req-1", "sig-a")).isEmpty();
        assertThat(store.get("req-1", "sig-b")).isEmpty();
        assertThat(store.get("req-2", "sig-a")).isPresent();
    }

    @Test
    void signatureIsOrderIndependent() {
        assertThat(ToolCheckpointStore.signature(
                ToolCall.of("t", Map.of("a", 1, "b", 2))))
                .isEqualTo(ToolCheckpointStore.signature(
                        ToolCall.of("t", Map.of("b", 2, "a", 1))));
        assertThat(ToolCheckpointStore.signature(
                ToolCall.of("t", Map.of("a", 1)))).isNotEqualTo(
                ToolCheckpointStore.signature(
                        ToolCall.of("t", Map.of("a", 2))));
    }

    @Test
    void disabledStoreNeverCaches() {
        ToolCheckpointStore store = new ToolCheckpointStore(false, 10_000, 100,
                System::currentTimeMillis);

        store.put("req-1", "sig", ok());

        assertThat(store.get("req-1", "sig")).isEmpty();
    }

    @Test
    void overflowEvictsOldest() {
        AtomicLong clock = new AtomicLong(1_000);
        ToolCheckpointStore store = new ToolCheckpointStore(true, 60_000, 2, clock::get);

        store.put("req-1", "sig-a", ok());
        clock.set(2_000);
        store.put("req-1", "sig-b", ok());
        clock.set(3_000);
        store.put("req-1", "sig-c", ok());

        assertThat(store.get("req-1", "sig-a")).isEmpty();
        assertThat(store.get("req-1", "sig-b")).isPresent();
        assertThat(store.get("req-1", "sig-c")).isPresent();
    }
}

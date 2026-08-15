package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：记忆 — 统计 / 列表 / 搜索 / 导入 / 删除 / 蒸馏 / 摘要 / 导出。 */
class MemoryE2ETest extends E2EBaseTest {

    @Test
    void memoryStats() throws Exception {
        Response r = client.get("/api/memory");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        // Java 契约：{"stats": {"long_term": {"count": N}, "short_term": {"count": N}}}
        assertThat(data.get("stats")).isInstanceOf(Map.class);
        Map<?, ?> stats = (Map<?, ?>) data.get("stats");
        assertThat(stats.containsKey("long_term") && stats.containsKey("short_term")).isTrue();
    }

    @Test
    void memoryListLongTerm() throws Exception {
        Response r = client.get("/api/memory/list?memory_type=long_term&limit=10");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.get("memories")).isInstanceOf(List.class);
    }

    @Test
    void memoryListShortTerm() throws Exception {
        Response r = client.get("/api/memory/list?memory_type=short_term&limit=10");
        assertThat(r.status()).isEqualTo(200);
        assertThat(client.json(r)).containsKey("memories");
    }

    @Test
    void memorySearch() throws Exception {
        Response r = client.get("/api/memory/search?q=test&limit=5");
        assertThat(r.status()).isEqualTo(200);
        assertThat(client.json(r)).containsKey("results");
    }

    @Test
    void memorySummaries() throws Exception {
        Response r = client.get("/api/memory/summaries?limit=10");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.get("summaries")).isInstanceOf(List.class);
    }

    @Test
    void memoryBatchImportAndDelete() throws Exception {
        Response r = client.post("/api/memory/batch-import", Map.of(
                "items", List.of(Map.of(
                        "content", "E2E_TEST_FACT: 这是一条E2E测试记忆",
                        "category", "fact",
                        "importance", 0.5))));
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.getOrDefault("imported_count", 0) instanceof Number
                && ((Number) data.getOrDefault("imported_count", 0)).intValue() >= 1)
                .isTrue();

        Map<String, Object> search = client.json(
                client.get("/api/memory/search?q=E2E_TEST_FACT&limit=5"));
        List<?> results = (List<?>) search.getOrDefault("results", List.of());
        for (Object item : results) {
            Map<?, ?> m = (Map<?, ?>) item;
            if (String.valueOf(m.get("content")).contains("E2E_TEST_FACT") && m.get("id") != null) {
                client.delete("/api/memory/" + m.get("id"));
            }
        }
    }

    @Test
    void memoryExportJson() throws Exception {
        Response r = client.get("/api/memory/export?format=json");
        assertThat(r.status()).isEqualTo(200);
        String contentType = r.header("content-type");
        String disposition = r.header("content-disposition");
        assertThat(contentType.toLowerCase().contains("json") || disposition.contains("attachment"))
                .isTrue();
    }

    @Test
    void memoryExportMarkdown() throws Exception {
        Response r = client.get("/api/memory/export?format=markdown");
        assertThat(r.status()).isEqualTo(200);
        String disposition = r.header("content-disposition");
        assertThat(disposition.contains(".md") || disposition.contains("attachment"))
                .isTrue();
    }

    @Test
    void memoryDistill() throws Exception {
        Response r = client.post("/api/memory/distill", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(client.json(r)).isInstanceOf(Map.class);
    }
}

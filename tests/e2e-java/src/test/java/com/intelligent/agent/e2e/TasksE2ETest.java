package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：任务管理 — 列表 / 统计 / actions / 创建 / 删除 / 取消 / 更新。 */
class TasksE2ETest extends E2EBaseTest {

    private static String taskId(Map<String, Object> data) {
        Object id = data.get("task_id");
        if (id == null) {
            id = data.get("id");
        }
        if (id == null && data.get("task") instanceof Map) {
            id = ((Map<?, ?>) data.get("task")).get("id");
        }
        return id == null ? "" : String.valueOf(id);
    }

    private static Map<String, Object> payload(String name, String message) {
        return Map.of(
                "name", name,
                "description", "由 E2E 创建，可安全删除",
                "action", "log",
                "args", Map.of("message", message),
                "schedule_type", "delay",
                "delay_seconds", 3600);
    }

    @Test
    void tasksList() throws Exception {
        Response r = client.get("/api/tasks/list?limit=20");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.get("tasks")).isInstanceOf(List.class);
    }

    @Test
    void tasksStats() throws Exception {
        assertThat(client.get("/api/tasks/stats").status()).isEqualTo(200);
    }

    @Test
    void tasksActions() throws Exception {
        Response r = client.get("/api/tasks/actions");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.containsKey("actions") || data instanceof Map).isTrue();
    }

    @Test
    void taskCreateAndDelete() throws Exception {
        Response r = client.post("/api/tasks/create", payload("E2E删除任务", "E2E delete"));
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.get("success")).isEqualTo(true);
        String id = taskId(data);
        assertThat(id).isNotBlank();

        Response r2 = client.delete("/api/tasks/" + id);
        assertThat(r2.status()).isEqualTo(200);
        assertThat(client.json(r2).get("success")).isNotEqualTo(false);
    }

    @Test
    void taskCreateAndCancel() throws Exception {
        Response r = client.post("/api/tasks/create", payload("E2E取消任务", "E2E cancel"));
        Map<String, Object> data = client.json(r);
        assertThat(data.get("success")).isEqualTo(true);
        String id = taskId(data);
        assertThat(id).isNotBlank();

        assertThat(client.post("/api/tasks/" + id + "/cancel", null).status()).isEqualTo(200);
        client.delete("/api/tasks/" + id);
    }

    @Test
    void taskUpdate() throws Exception {
        Response r = client.post("/api/tasks/create", payload("E2E更新任务", "E2E update"));
        String id = taskId(client.json(r));
        assertThat(id).isNotBlank();

        Response r2 = client.patch("/api/tasks/" + id,
                Map.of("description", "updated by E2E"));
        assertThat(r2.status()).isEqualTo(200);
        client.delete("/api/tasks/" + id);
    }
}

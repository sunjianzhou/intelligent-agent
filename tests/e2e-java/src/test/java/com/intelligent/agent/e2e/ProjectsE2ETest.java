package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：项目 — 列表 / CRUD / Spec / 任务树。 */
class ProjectsE2ETest extends E2EBaseTest {

    private static String projectId(Map<String, Object> data) {
        Object id = data.get("project_id");
        if (id == null) {
            id = data.get("id");
        }
        if (id == null && data.get("project") instanceof Map) {
            id = ((Map<?, ?>) data.get("project")).get("id");
        }
        return id == null ? "" : String.valueOf(id);
    }

    @Test
    void listProjects() throws Exception {
        Response r = client.get("/api/projects");
        assertThat(r.status()).isEqualTo(200);
        assertThat(client.json(r)).containsKey("projects");
    }

    @Test
    void projectCrud() throws Exception {
        Response r = client.post("/api/projects",
                Map.of("title", "E2E测试项目", "description", "由E2E创建"));
        assertThat(r.status()).isEqualTo(200);
        String id = projectId(client.json(r));
        assertThat(id).isNotBlank();
        try {
            assertThat(client.get("/api/projects/" + id).status()).isEqualTo(200);
            assertThat(client.put("/api/projects/" + id,
                    Map.of("title", "E2E更新项目", "description", "updated")).status())
                    .isEqualTo(200);
        } finally {
            client.delete("/api/projects/" + id);
        }
    }

    @Test
    void projectSpecPutAndGet() throws Exception {
        Response r = client.post("/api/projects", Map.of("title", "E2E Spec项目"));
        String id = projectId(client.json(r));
        assertThat(id).isNotBlank();
        try {
            Response r2 = client.put("/api/project/spec", Map.of(
                    "project_id", id,
                    "spec", "# E2E Spec\n\n这是一个测试规格"));
            assertThat(r2.status()).isEqualTo(200);
            Response r3 = client.get("/spec?project_id=" + id);
            assertThat(r3.status()).isEqualTo(200);
        } finally {
            client.delete("/api/projects/" + id);
        }
    }

    @Test
    void projectTasksList() throws Exception {
        Response r = client.post("/api/projects", Map.of("title", "E2E Tasks项目"));
        String id = projectId(client.json(r));
        assertThat(id).isNotBlank();
        try {
            Response r2 = client.get("/api/project/tasks?project_id=" + id);
            assertThat(r2.status()).isEqualTo(200);
            assertThat(client.json(r2)).containsKey("tasks");
        } finally {
            client.delete("/api/projects/" + id);
        }
    }
}

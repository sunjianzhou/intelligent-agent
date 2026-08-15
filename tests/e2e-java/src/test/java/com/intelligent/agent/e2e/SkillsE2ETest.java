package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：Skill 管理 — 列表 / 模板 / CRUD / 过滤。 */
class SkillsE2ETest extends E2EBaseTest {

    private static String skillId(Map<String, Object> data) {
        Object id = data.get("id");
        if (id == null) {
            id = data.get("skill_id");
        }
        if (id == null && data.get("skill") instanceof Map) {
            id = ((Map<?, ?>) data.get("skill")).get("id");
        }
        return id == null ? "" : String.valueOf(id);
    }

    @Test
    void skillsList() throws Exception {
        Response r = client.get("/api/skills");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.get("skills")).isInstanceOf(List.class);
    }

    @Test
    void skillTemplates() throws Exception {
        Response r = client.get("/api/skills/templates/list");
        assertThat(r.status()).isEqualTo(200);
        assertThat(client.json(r)).isInstanceOf(Map.class);
    }

    @Test
    void skillCrud() throws Exception {
        Map<String, Object> payload = Map.of(
                "name", "E2E测试Skill",
                "description", "由E2E测试创建，可安全删除",
                "trigger_keywords", List.of("e2e", "test"),
                "enabled", true);
        Response r = client.post("/api/skills", payload);
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.get("success")).isEqualTo(true);
        String id = skillId(data);
        assertThat(id).as("创建未返回 id: %s", data).isNotBlank();

        Map<String, Object> update = new java.util.LinkedHashMap<>(payload);
        update.put("description", "updated by E2E");
        assertThat(client.put("/api/skills/" + id, update).status()).isEqualTo(200);
        assertThat(client.patch("/api/skills/" + id + "/toggle", null).status()).isEqualTo(200);

        Response r4 = client.delete("/api/skills/" + id);
        assertThat(r4.status()).isEqualTo(200);
        assertThat(client.json(r4).get("success")).isNotEqualTo(false);
    }

    @Test
    void skillsFilterByTag() throws Exception {
        assertThat(client.get("/api/skills?tag=test").status()).isEqualTo(200);
    }

    @Test
    void skillsEnabledOnly() throws Exception {
        Response r = client.get("/api/skills?enabled_only=true");
        assertThat(r.status()).isEqualTo(200);
        List<?> skills = (List<?>) client.json(r).get("skills");
        for (Object skill : skills) {
            assertThat(((Map<?, ?>) skill).get("enabled")).isEqualTo(true);
        }
    }
}

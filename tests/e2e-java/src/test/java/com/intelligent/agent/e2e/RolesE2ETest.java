package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：角色管理 — 列表 / 激活 / 取消激活。 */
class RolesE2ETest extends E2EBaseTest {

    @Test
    void listRoles() throws Exception {
        Response r = client.get("/api/roles");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data.get("roles")).isInstanceOf(List.class);
    }

    @Test
    void getActiveRole() throws Exception {
        Response r = client.get("/api/roles/activate");
        assertThat(r.status()).isEqualTo(200);
        assertThat(client.json(r)).isInstanceOf(Map.class);
    }

    @Test
    void activateAndDeactivateRole() throws Exception {
        List<?> roles = (List<?>) client.json(client.get("/api/roles")).get("roles");
        Assumptions.assumeFalse(roles.isEmpty(), "没有可用角色，跳过激活测试");
        Object roleId = ((Map<?, ?>) roles.get(0)).get("role_id");
        if (roleId == null) {
            roleId = ((Map<?, ?>) roles.get(0)).get("id");
        }
        assertThat(roleId).isNotNull();

        Response r2 = client.post("/api/roles/activate", Map.of("role_id", String.valueOf(roleId)));
        assertThat(r2.status()).isEqualTo(200);
        assertThat(client.json(r2).get("success")).isNotEqualTo(false);

        Map<String, Object> active = client.json(client.get("/api/roles/activate"));
        Object activeId = active.get("role_id");
        if (activeId == null) {
            activeId = active.get("active_role_id");
        }
        assertThat(String.valueOf(activeId)).isEqualTo(String.valueOf(roleId));

        assertThat(client.delete("/api/roles/activate").status()).isEqualTo(200);
    }

    @Test
    void activateNonexistentRole() throws Exception {
        Response r = client.post("/api/roles/activate", Map.of("role_id", "nonexistent-xyz"));
        assertThat(r.status()).isIn(200, 404, 500);
        if (r.status() == 200) {
            assertThat(client.json(r).get("success")).isEqualTo(false);
        }
    }
}

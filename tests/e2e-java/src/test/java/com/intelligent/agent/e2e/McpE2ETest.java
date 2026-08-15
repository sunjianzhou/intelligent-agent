package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E：MCP 服务器管理（G2）— 配置 CRUD；连接失败时优雅返回 success=false。
 */
class McpE2ETest extends E2EBaseTest {

    @Test
    void serverCrudAndGracefulConnectFailure() throws Exception {
        Response r = client.post("/api/mcp/servers", Map.of(
                "name", "E2E-MCP",
                "base_url", "http://127.0.0.1:1",
                "api_key", "sk-e2e",
                "enabled", false));
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> created = client.json(r);
        assertThat(created.get("success")).isEqualTo(true);
        Map<?, ?> server = (Map<?, ?>) created.get("server");
        String id = String.valueOf(server.get("id"));
        assertThat(id).isNotBlank();

        // 列表可见
        List<?> servers = (List<?>) client.json(client.get("/api/mcp/servers")).get("servers");
        List<Object> names = new java.util.ArrayList<>();
        for (Object s : servers) {
            names.add(((Map<?, ?>) s).get("name"));
        }
        assertThat(names).contains("E2E-MCP");

        // 不可达服务器连接失败但接口正常（success=false）
        Response rConnect = client.post("/api/mcp/servers/" + id + "/connect", null);
        assertThat(rConnect.status()).isEqualTo(200);
        assertThat(client.json(rConnect).get("success")).isEqualTo(false);

        // 删除
        Response rDel = client.delete("/api/mcp/servers/" + id);
        assertThat(rDel.status()).isEqualTo(200);
        assertThat(client.json(rDel).get("success")).isEqualTo(true);
    }
}

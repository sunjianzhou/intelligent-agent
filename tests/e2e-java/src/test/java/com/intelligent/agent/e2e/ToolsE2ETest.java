package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：工具列表。 */
class ToolsE2ETest extends E2EBaseTest {

    @Test
    void toolsListNonEmptyWithNames() throws Exception {
        Response r = client.get("/api/tools/list");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data).containsKey("tools");
        Object tools = data.get("tools");
        assertThat(tools).isInstanceOf(List.class);
        assertThat((List<?>) tools).isNotEmpty();
        for (Object tool : (List<?>) tools) {
            assertThat(tool).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) tool;
            assertThat(entry).containsKey("name");
        }
    }
}

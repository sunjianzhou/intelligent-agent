package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：通知轮询。 */
class NotificationsE2ETest extends E2EBaseTest {

    @Test
    void pollNotificationsShape() throws Exception {
        Response r = client.get("/api/notifications/poll");
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = client.json(r);
        assertThat(data).containsKey("notifications");
        assertThat(data).containsKey("count");
        assertThat(data.get("notifications")).isInstanceOf(List.class);
        assertThat(((List<?>) data.get("notifications")).size())
                .isEqualTo(((Number) data.get("count")).intValue());
    }
}

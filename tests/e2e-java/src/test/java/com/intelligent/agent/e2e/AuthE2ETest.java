package com.intelligent.agent.e2e;

import com.intelligent.agent.e2e.ApiClient.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E：认证 — 登录 / 登出 / 鉴权拦截。 */
class AuthE2ETest extends E2EBaseTest {

    @Test
    void loginSuccess() throws Exception {
        ApiClient anon = new ApiClient(BASE_URL, Duration.ofSeconds(10));
        Response r = anon.post("/api/auth/login",
                Map.of("username", USERNAME, "password", PASSWORD));

        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> data = anon.json(r);
        assertThat(data.get("success")).isEqualTo(true);
        assertThat(data.get("token")).isNotNull();
        assertThat(data.get("username")).isEqualTo(USERNAME);
    }

    @Test
    void loginWrongPassword() throws Exception {
        ApiClient anon = new ApiClient(BASE_URL, Duration.ofSeconds(10));
        Response r = anon.post("/api/auth/login",
                Map.of("username", USERNAME, "password", "wrong"));

        assertThat(r.status()).isEqualTo(401);
        assertThat(anon.json(r).get("success")).isEqualTo(false);
    }

    @Test
    void loginMissingUser() throws Exception {
        ApiClient anon = new ApiClient(BASE_URL, Duration.ofSeconds(10));
        Response r = anon.post("/api/auth/login",
                Map.of("username", "nobody", "password", "x"));
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void protectedEndpointWithoutToken() throws Exception {
        ApiClient anon = new ApiClient(BASE_URL, Duration.ofSeconds(10));
        Response r = anon.get("/api/memory");
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void logout() throws Exception {
        Response r = client.post("/api/auth/logout", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(client.json(r).get("success")).isEqualTo(true);
    }
}

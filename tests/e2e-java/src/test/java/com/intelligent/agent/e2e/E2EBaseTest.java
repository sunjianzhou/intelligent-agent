package com.intelligent.agent.e2e;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import java.time.Duration;

/**
 * E2E 基类：后端不可达时整类跳过（对应 pytest java_up fixture）。
 * 环境变量覆盖：E2E_BASE_URL / E2E_USERNAME / E2E_PASSWORD / E2E_CHAT_TIMEOUT。
 */
public abstract class E2EBaseTest {

    protected static final String BASE_URL = System.getenv().getOrDefault(
            "E2E_BASE_URL", "http://localhost:8080");
    protected static final String USERNAME = System.getenv().getOrDefault(
            "E2E_USERNAME", "admin");
    protected static final String PASSWORD = System.getenv().getOrDefault(
            "E2E_PASSWORD", "admin123");
    protected static final int CHAT_TIMEOUT = Integer.parseInt(
            System.getenv().getOrDefault("E2E_CHAT_TIMEOUT", "300"));

    protected static ApiClient client;
    protected static ApiClient slowClient;

    @BeforeAll
    static void e2eSetUp() throws Exception {
        ApiClient probe = new ApiClient(BASE_URL, Duration.ofSeconds(5));
        Assumptions.assumeTrue(probe.reachable(),
                "Java backend not reachable at " + BASE_URL);
        client = ApiClient.login(BASE_URL, USERNAME, PASSWORD, Duration.ofSeconds(10));
        slowClient = ApiClient.login(BASE_URL, USERNAME, PASSWORD,
                Duration.ofSeconds(Math.max(30, CHAT_TIMEOUT)));
    }
}

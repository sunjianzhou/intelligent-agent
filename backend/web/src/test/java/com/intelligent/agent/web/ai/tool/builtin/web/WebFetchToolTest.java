package com.intelligent.agent.web.ai.tool.builtin.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-03 WebFetchTool 正常路径测试：正文抓取、标题、截断、白名单拒绝、重定向跟随与上限。
 */
class WebFetchToolTest {

    private HttpServer server;
    private int port;

    /** 关闭 SSRF 的 happy-path 实例（SSRF 校验单独在 SecurityTest 覆盖）。 */
    private final WebFetchTool tool = new WebFetchTool(
            List.of("127.0.0.1"), 5, 100, 5, 1024 * 1024, false,
            host -> new java.net.InetAddress[]{
                    java.net.InetAddress.getByName("127.0.0.1")});

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ("<html><head><title>测试页面</title></head>"
                    + "<body><nav>导航</nav><p>第一段正文内容。</p>"
                    + "<script>alert(1)</script><p>第二段正文内容。</p></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/big", exchange -> {
            String content = "长正文".repeat(5000); // 15000 字符，超过 100 上限
            byte[] body = content.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            byte[] body = ("<html><head><title>目标页</title></head>"
                    + "<body><p>重定向后的目标内容。</p></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Object raw) {
        return (Map<String, Object>) raw;
    }

    @Test
    void fetchesTitleAndBodyText() {
        Map<String, Object> result = result(tool.execute(Map.of("url", url("/"))));

        assertThat(result.get("title")).isEqualTo("测试页面");
        assertThat(String.valueOf(result.get("text")))
                .contains("第一段正文内容", "第二段正文内容")
                .doesNotContain("导航", "alert"); // script/nav 已剔除
    }

    @Test
    void truncatesOversizedBody() {
        Map<String, Object> result = result(tool.execute(Map.of("url", url("/big"))));

        assertThat(String.valueOf(result.get("text"))).contains("截断");
        assertThat(String.valueOf(result.get("text")).length())
                .isLessThanOrEqualTo(100 + 40);
    }

    @Test
    void rejectsNonWhitelistedDomain() {
        Map<String, Object> result = result(tool.execute(Map.of("url", "http://example.com/")));

        assertThat(result).containsKey("error");
        assertThat(String.valueOf(result.get("error"))).contains("白名单");
    }

    @Test
    void followsRedirectWithinLimit() {
        Map<String, Object> result = result(tool.execute(Map.of("url", url("/redirect"))));

        assertThat(result.get("title")).isEqualTo("目标页");
        assertThat(String.valueOf(result.get("text"))).contains("重定向后的目标内容");
        assertThat(String.valueOf(result.get("url"))).endsWith("/target");
    }

    @Test
    void rejectsRedirectLoopBeyondLimit() {
        Map<String, Object> result = result(tool.execute(Map.of("url", url("/loop"))));

        assertThat(result).containsKey("error");
        assertThat(String.valueOf(result.get("error"))).contains("重定向次数超过上限");
    }

    @Test
    void rejectsBlankUrl() {
        Map<String, Object> result = result(tool.execute(Map.of("url", "  ")));

        assertThat(String.valueOf(result.get("error"))).contains("url 不能为空");
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}

package com.intelligent.agent.web.ai.tool.builtin.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-03 WebFetchTool 安全测试：私网/保留地址拒绝、DNS 解析注入、每跳重定向重新校验。
 */
class WebFetchToolSecurityTest {

    /** 假解析器：allowed.example → 公网 IP；internal.local → 127.0.0.1。 */
    private final WebFetchTool tool = new WebFetchTool(
            List.of("allowed.example", "internal.local"), 5, 100, 5, 1024 * 1024, true,
            host -> {
                if ("allowed.example".equals(host)) {
                    return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
                }
                if ("internal.local".equals(host)) {
                    return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
                }
                return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
            });

    @Test
    void privateIpIsRejectedEvenWhenWhitelisted() {
        WebFetchTool strict = new WebFetchTool(
                List.of("127.0.0.1"), 5, 100, 5, 1024 * 1024, true,
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});

        Object result = strict.execute(Map.of("url", "http://127.0.0.1:8080/"));

        assertThat(String.valueOf(((Map<?, ?>) result).get("error")))
                .contains("内网/保留地址");
    }

    @Test
    void hostnameResolvingToPrivateIpIsRejected() {
        assertThatThrownBy(() -> tool.vetUrl("http://internal.local:8080/"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("内网/保留地址");
    }

    @Test
    void redirectTargetIsReVettedPerHop() throws Exception {
        // 第一跳：公网 IP，通过校验
        tool.vetUrl("http://allowed.example/page");

        // 重定向到 internal.local → 第二跳重新解析并拒绝（防 DNS rebinding / 跳转绕过）
        String redirected = WebFetchTool.resolve(
                "http://allowed.example/page", "http://internal.local/secret");
        assertThatThrownBy(() -> tool.vetUrl(redirected))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("内网/保留地址");
    }

    @Test
    void nonHttpSchemeRejected() {
        assertThatThrownBy(() -> tool.vetUrl("file:///etc/passwd"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("仅支持 http/https");
        assertThatThrownBy(() -> tool.vetUrl("ftp://allowed.example/x"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("仅支持 http/https");
    }

    @Test
    void blockedAddressRanges() throws Exception {
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("10.0.0.1"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("172.16.0.1"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("192.168.1.1"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("169.254.169.254"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("100.64.0.1"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("192.0.0.1"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("192.0.2.1"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("198.18.0.1"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("198.51.100.1"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("203.0.113.1"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("224.0.0.1"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("240.0.0.1"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("0.0.0.0"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("::1"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("fc00::1"))).isTrue();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("fe80::1"))).isTrue();

        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("8.8.8.8"))).isFalse();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("93.184.216.34"))).isFalse();
        assertThat(WebFetchTool.isBlockedAddress(InetAddress.getByName("1.1.1.1"))).isFalse();
    }
}

package com.intelligent.agent.web.ai.tool.builtin.web;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出网代理解析契约（2026-11-03 web-001 根因：Java 不读取 Windows 系统代理）。
 * 只测纯解析，不发起网络请求。
 */
class WebProxyResolverTest {

    private static InetSocketAddress addressOf(Proxy proxy) {
        SocketAddress address = proxy.address();
        return address instanceof InetSocketAddress inet ? inet : null;
    }

    @Test
    void parsesHostPort() {
        Proxy proxy = WebProxyResolver.parse("127.0.0.1:15568");

        assertThat(proxy).isNotNull();
        assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(addressOf(proxy).getHostString()).isEqualTo("127.0.0.1");
        assertThat(addressOf(proxy).getPort()).isEqualTo(15568);
    }

    @Test
    void parsesHttpAndHttpsScheme() {
        for (String spec : new String[]{"http://127.0.0.1:7890", "https://127.0.0.1:7890"}) {
            Proxy proxy = WebProxyResolver.parse(spec);
            assertThat(proxy).isNotNull();
            assertThat(addressOf(proxy).getPort()).isEqualTo(7890);
        }
    }

    @Test
    void parsesWindowsCompoundProxyServer() {
        Proxy proxy = WebProxyResolver.parse("http=127.0.0.1:8080;https=127.0.0.1:8443");

        assertThat(proxy).isNotNull();
        assertThat(addressOf(proxy).getPort()).isEqualTo(8443);
    }

    @Test
    void parsesSocksProxy() {
        Proxy proxy = WebProxyResolver.parse("socks5://127.0.0.1:1080");

        assertThat(proxy).isNotNull();
        assertThat(proxy.type()).isEqualTo(Proxy.Type.SOCKS);
        assertThat(addressOf(proxy).getPort()).isEqualTo(1080);
    }

    @Test
    void stripsCredentialsAndPath() {
        Proxy proxy = WebProxyResolver.parse("http://user:pass@127.0.0.1:3128/proxy.pac");

        assertThat(proxy).isNotNull();
        assertThat(addressOf(proxy).getHostString()).isEqualTo("127.0.0.1");
        assertThat(addressOf(proxy).getPort()).isEqualTo(3128);
    }

    @Test
    void parsesIpv6InBrackets() {
        Proxy proxy = WebProxyResolver.parse("[::1]:8080");

        assertThat(proxy).isNotNull();
        assertThat(addressOf(proxy).getAddress().isLoopbackAddress()).isTrue();
        assertThat(addressOf(proxy).getPort()).isEqualTo(8080);
    }

    @Test
    void rejectsBlankAndInvalidPort() {
        assertThat(WebProxyResolver.parse(null)).isNull();
        assertThat(WebProxyResolver.parse("  ")).isNull();
        assertThat(WebProxyResolver.parse("127.0.0.1:700000")).isNull();
    }

    @Test
    void localHostSkipsProxy() {
        assertThat(WebSearchTool.isLocalHost("http://localhost:18080/?q=")).isTrue();
        assertThat(WebSearchTool.isLocalHost("http://127.0.0.1:18080/?q=")).isTrue();
        assertThat(WebSearchTool.isLocalHost("https://html.duckduckgo.com/html/?q=")).isFalse();
        assertThat(WebSearchTool.isLocalHost("not a url")).isFalse();
    }
}

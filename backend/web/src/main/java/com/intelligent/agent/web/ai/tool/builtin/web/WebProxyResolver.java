package com.intelligent.agent.web.ai.tool.builtin.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 出网代理解析（web_search / web_fetch 用）。
 *
 * <p>背景：Java 的 {@code HttpClient}/{@code Jsoup} 默认**不读取** Windows 的系统代理
 * （WinINET 设置只对浏览器/部分 .NET 应用生效）。本机开着 Clash/V2Ray 一类系统代理时，
 * PowerShell/浏览器能访问外网，Java 却直连失败——2026-11-03 eval web-001 现场：
 * 系统代理 {@code 127.0.0.1:15568}，`web_search` 必然 error。</p>
 *
 * <p>解析优先级（先命中先用）：</p>
 * <ol>
 *   <li>显式配置 {@code ai.web.proxy} / 环境变量 {@code WEB_SEARCH_PROXY}；</li>
 *   <li>环境变量 {@code HTTPS_PROXY} / {@code HTTP_PROXY}（CI、Linux/macOS 常见）；</li>
 *   <li>JVM 参数 {@code https.proxyHost/Port}、{@code http.proxyHost/Port}；</li>
 *   <li>Windows 系统代理（注册表 {@code ProxyEnable}/{@code ProxyServer}，只读一次并缓存）。</li>
 * </ol>
 *
 * <p>不支持的形态（需要认证的代理、PAC 脚本）返回 null，调用方按直连处理并在日志里提示。</p>
 */
public final class WebProxyResolver {

    private static final Logger log = LoggerFactory.getLogger(WebProxyResolver.class);

    /**
     * 注册表解析结果缓存（含"没有代理"的负结果）：避免每次搜索都起进程；
     * 5 分钟后重新探测，用户中途打开/关闭 Clash 一类系统代理无需重启后端。
     */
    private static final long REGISTRY_CACHE_MS = 5 * 60 * 1000L;
    private static volatile Proxy windowsProxy;
    private static volatile long windowsProxyExpiresAt;

    private WebProxyResolver() {
    }

    /** 按优先级解析代理；没有任何可用配置时返回 null（直连）。 */
    public static Proxy resolve(String explicitSpec) {
        Proxy explicit = parse(explicitSpec);
        if (explicit != null) {
            return explicit;
        }
        Proxy env = parse(firstNonBlank(System.getenv("HTTPS_PROXY"),
                System.getenv("https_proxy"), System.getenv("HTTP_PROXY"),
                System.getenv("http_proxy")));
        if (env != null) {
            return env;
        }
        Proxy props = fromSystemProperties();
        if (props != null) {
            return props;
        }
        return fromWindowsRegistry();
    }

    /**
     * 解析代理串。支持：
     * <ul>
     *   <li>{@code host:port}、{@code http://host:port}、{@code socks5://host:port}；</li>
     *   <li>Windows {@code ProxyServer} 复合格式 {@code http=h:p;https=h:p}（优先 https）。</li>
     * </ul>
     */
    static Proxy parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String value = spec.trim();
        if (value.contains("=") && value.contains(";")) {
            String https = null;
            String http = null;
            for (String part : value.split(";")) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                String key = kv[0].trim().toLowerCase(Locale.ROOT);
                if ("https".equals(key)) {
                    https = kv[1].trim();
                } else if ("http".equals(key)) {
                    http = kv[1].trim();
                }
            }
            return parse(https != null ? https : http);
        }
        if (value.contains("=") && !value.contains("://")) {
            // 单个 http=host:port
            int eq = value.indexOf('=');
            value = value.substring(eq + 1).trim();
        }
        Proxy.Type type = Proxy.Type.HTTP;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("socks5://") || lower.startsWith("socks://")) {
            type = Proxy.Type.SOCKS;
            value = value.substring(value.indexOf("://") + 3);
        } else if (lower.startsWith("http://") || lower.startsWith("https://")) {
            value = value.substring(value.indexOf("://") + 3);
        }
        // 去掉路径 / 认证信息（不支持需要认证的代理）
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int at = value.indexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        String host = value;
        int port = type == Proxy.Type.SOCKS ? 1080 : 80;
        int colon = value.lastIndexOf(':');
        if (colon > 0 && colon < value.length() - 1) {
            String portText = value.substring(colon + 1);
            if (portText.chars().allMatch(Character::isDigit)) {
                host = value.substring(0, colon);
                port = Integer.parseInt(portText);
            }
        }
        if (host.isBlank() || host.contains(":") && !host.startsWith("[")) {
            // 裸 IPv6 不支持，需写成 [::1]:port
            return null;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (port <= 0 || port > 65535) {
            return null;
        }
        return new Proxy(type, new InetSocketAddress(host, port));
    }

    private static Proxy fromSystemProperties() {
        Proxy https = parse(hostPort(System.getProperty("https.proxyHost"),
                System.getProperty("https.proxyPort")));
        if (https != null) {
            return https;
        }
        return parse(hostPort(System.getProperty("http.proxyHost"),
                System.getProperty("http.proxyPort")));
    }

    private static String hostPort(String host, String port) {
        if (host == null || host.isBlank()) {
            return null;
        }
        return port == null || port.isBlank() ? host.trim() : host.trim() + ":" + port.trim();
    }

    /**
     * Windows 系统代理（WinINET）：{@code HKCU\...\Internet Settings} 的
     * {@code ProxyEnable=1} 时读取 {@code ProxyServer}。非 Windows / 读取失败返回 null。
     */
    private static Proxy fromWindowsRegistry() {
        if (System.currentTimeMillis() < windowsProxyExpiresAt) {
            return windowsProxy;
        }
        synchronized (WebProxyResolver.class) {
            long now = System.currentTimeMillis();
            if (now < windowsProxyExpiresAt) {
                return windowsProxy;
            }
            Proxy resolved = null;
            try {
                if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                    String enable = regQuery("ProxyEnable");
                    if (enable != null && enable.replace(" ", "").endsWith("0x1")) {
                        String server = regQuery("ProxyServer");
                        resolved = parse(server);
                        if (resolved != null) {
                            log.info("[web] 使用 Windows 系统代理 {}", resolved.address());
                        } else if (server != null && !server.isBlank()) {
                            log.warn("[web] 检测到系统代理但无法解析：{}"
                                    + "（可通过 WEB_SEARCH_PROXY 显式配置）", server);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[web] 读取系统代理失败：{}", e.getMessage());
            }
            windowsProxy = resolved;
            windowsProxyExpiresAt = now + REGISTRY_CACHE_MS;
            return resolved;
        }
    }

    /** 读一个注册表值，返回最后一列（如 {@code 0x1} 或 {@code 127.0.0.1:15568}）。 */
    private static String regQuery(String valueName) throws Exception {
        Process process = new ProcessBuilder("reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                "/v", valueName).redirectErrorStream(true).start();
        // 先等进程退出再读输出：输出只有一行（<4KB 管道缓冲），不会阻塞写入
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return null;
        }
        String output = new String(process.getInputStream().readAllBytes());
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith(valueName.toLowerCase(Locale.ROOT))) {
                String[] columns = trimmed.split("\\s+");
                return columns.length >= 3 ? columns[columns.length - 1] : null;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

package com.intelligent.agent.web.ai.tool.builtin.web;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * R-03 网页正文抓取工具：白名单域名 + SSRF 防护（含每跳重定向校验）+ 1MB 响应上限 +
 * 正文截断。抓取结果由编排器统一加"不可信数据"前缀后再喂 LLM（G2 注入防护），此处不再重复。
 */
public class WebFetchTool implements AgentTool {

    public static final int DEFAULT_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_MAX_BODY_CHARS = 8000;
    public static final int DEFAULT_MAX_REDIRECTS = 5;
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;
    public static final String USER_AGENT = "intelligent-agent-web-fetch/1.0";

    private final List<String> allowedDomains;
    private final int timeoutSeconds;
    private final int maxBodyChars;
    private final int maxRedirects;
    private final long maxResponseBytes;
    private final boolean enforceSsr;
    private final HostResolver resolver;
    private final CloseableHttpClient httpClient;

    /** 默认 SSRF 防护开启；白名单为空时全部拒绝（需在配置中显式声明）。 */
    public WebFetchTool(List<String> allowedDomains) {
        this(allowedDomains, DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_BODY_CHARS);
    }

    public WebFetchTool(List<String> allowedDomains, int timeoutSeconds, int maxBodyChars) {
        this(allowedDomains, timeoutSeconds, maxBodyChars,
                DEFAULT_MAX_REDIRECTS, DEFAULT_MAX_RESPONSE_BYTES, true,
                InetAddress::getAllByName);
    }

    /** 测试构造：可关闭 SSRF / 注入自定义 DNS 解析器（安全测试用）。 */
    WebFetchTool(List<String> allowedDomains, int timeoutSeconds, int maxBodyChars,
                 int maxRedirects, long maxResponseBytes, boolean enforceSsr,
                 HostResolver resolver) {
        this.allowedDomains = allowedDomains == null
                ? List.of() : allowedDomains.stream()
                .filter(d -> d != null && !d.isBlank())
                .map(d -> stripTrailingDot(d.trim().toLowerCase(Locale.ROOT)))
                .toList();
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        this.maxBodyChars = maxBodyChars > 0 ? maxBodyChars : DEFAULT_MAX_BODY_CHARS;
        this.maxRedirects = maxRedirects > 0 ? maxRedirects : DEFAULT_MAX_REDIRECTS;
        this.maxResponseBytes = maxResponseBytes > 0
                ? maxResponseBytes : DEFAULT_MAX_RESPONSE_BYTES;
        this.enforceSsr = enforceSsr;
        this.resolver = resolver == null ? InetAddress::getAllByName : resolver;
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(this.timeoutSeconds))
                .setResponseTimeout(Timeout.ofSeconds(this.timeoutSeconds))
                .setConnectionRequestTimeout(Timeout.ofSeconds(10))
                .build();
        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(config)
                .disableRedirectHandling()
                .build();
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("url", Map.of(
                        "type", "string",
                        "description", "要抓取正文的完整 URL（仅 http/https，白名单域名）")),
                "required", List.of("url"));
        return new ToolDefinition(
                "web_fetch",
                "抓取网页正文内容（白名单域名，SSRF 防护，正文截断）。参数: url(必填)。"
                        + "返回该页标题与正文文本，供调研/引用。",
                true, null, Duration.ofSeconds(20), schema);
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        String url = String.valueOf(arguments.getOrDefault("url", "")).trim();
        if (url.isBlank()) {
            return error("url 不能为空");
        }
        try {
            return fetch(url);
        } catch (Exception e) {
            return error("抓取失败: " + safeMessage(e));
        }
    }

    private Map<String, Object> fetch(String url) throws IOException {
        String current = url;
        for (int hop = 0; hop <= maxRedirects; hop++) {
            vetUrl(current);
            try (CloseableHttpResponse response = httpClient.execute(get(current))) {
                int status = response.getCode();
                if (status >= 300 && status < 400) {
                    String location = response.getFirstHeader("Location") == null
                            ? null : response.getFirstHeader("Location").getValue();
                    if (location == null || location.isBlank()) {
                        return error("重定向缺少 Location 头");
                    }
                    // 每个重定向 hop 重新解析并重复校验（防 DNS rebinding / 跳转绕过）
                    current = resolve(current, location);
                    continue;
                }
                if (status != 200) {
                    return error("HTTP " + status);
                }
                return extract(current, readCapped(response));
            }
        }
        return error("重定向次数超过上限 " + maxRedirects);
    }

    private static HttpGet get(String url) {
        HttpGet request = new HttpGet(url);
        request.setHeader("User-Agent", USER_AGENT);
        request.setHeader("Accept", "text/html,application/xhtml+xml");
        return request;
    }

    private byte[] readCapped(CloseableHttpResponse response) throws IOException {
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            return new byte[0];
        }
        long declared = entity.getContentLength();
        if (declared > maxResponseBytes) {
            throw new IOException("响应体超过 " + (maxResponseBytes / 1024) + "KB 上限");
        }
        try (InputStream in = entity.getContent()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxResponseBytes) {
                    throw new IOException("响应体超过 " + (maxResponseBytes / 1024) + "KB 上限");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private Map<String, Object> extract(String url, byte[] body) throws IOException {
        Document doc = Jsoup.parse(new ByteArrayInputStream(body), null, url);
        doc.select("script, style, nav, header, footer, form, aside, iframe, noscript, svg").remove();
        String title = doc.title();
        String text = doc.body() != null ? doc.body().text() : doc.text();
        if (text.length() > maxBodyChars) {
            text = text.substring(0, maxBodyChars)
                    + "\n…(正文过长已截断，共 " + body.length + " 字节)";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        result.put("title", title.isBlank() ? "(无标题)" : title);
        result.put("text", text);
        return result;
    }

    /** 校验 URL：协议 + 白名单域名 + SSRF（解析后 IP 校验）；每跳调用一次。 */
    void vetUrl(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IOException("URL 格式非法");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme))) {
            throw new IOException("仅支持 http/https 协议");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("URL 缺少主机名");
        }
        host = stripTrailingDot(host.toLowerCase(Locale.ROOT));
        if (!isAllowed(host)) {
            throw new IOException("域名不在白名单: " + host);
        }
        if (enforceSsr) {
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses == null || addresses.length == 0) {
                throw new IOException("域名解析失败: " + host);
            }
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new IOException("目标地址为内网/保留地址，已拒绝: "
                            + address.getHostAddress());
                }
            }
        }
    }

    private boolean isAllowed(String host) {
        for (String domain : allowedDomains) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    /** 相对/绝对 Location 解析为下一个请求 URL（协议/主机校验留给下一跳 vetUrl）。 */
    static String resolve(String baseUrl, String location) {
        URI base = URI.create(baseUrl);
        URI resolved = base.resolve(location);
        return resolved.toString();
    }

    /** 私网/环回/链路本地/保留地址判定（SSRF 防护核心，安全测试直接覆盖）。 */
    static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        if (address instanceof java.net.Inet6Address) {
            byte[] b = address.getAddress();
            // fc00::/7 ULA（JDK isSiteLocalAddress 仅覆盖已弃用的 fec0::/10）
            if ((b[0] & 0xFE) == 0xFC) {
                return true;
            }
            // fec0::/10 已弃用 site-local
            if (b[0] == (byte) 0xFE && (b[1] & 0xC0) == 0xC0) {
                return true;
            }
        }
        if (address instanceof Inet4Address) {
            byte[] b = address.getAddress();
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            int b2 = b[2] & 0xFF;
            if (b0 == 0) {
                return true;                       // 0.0.0.0/8
            }
            if (b0 == 100 && (b1 & 0xC0) == 0x40) {
                return true;                       // 100.64.0.0/10 CGNAT
            }
            if (b0 == 192 && b1 == 0 && b2 == 0) {
                return true;                       // 192.0.0.0/24
            }
            if (b0 == 192 && b1 == 0 && b2 == 2) {
                return true;                       // 192.0.2.0/24 TEST-NET-1
            }
            if (b0 == 198 && (b1 & 0xFE) == 0x12) {
                return true;                       // 198.18.0.0/15 基准测试
            }
            if (b0 == 198 && b1 == 51 && b2 == 100) {
                return true;                       // 198.51.100.0/24 TEST-NET-2
            }
            if (b0 == 203 && b1 == 0 && b2 == 113) {
                return true;                       // 203.0.113.0/24 TEST-NET-3
            }
            if (b0 >= 224) {
                return true;                       // 组播 / 保留
            }
        }
        return false;
    }

    private static String stripTrailingDot(String host) {
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", message);
        return result;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    /** DNS 解析抽象（默认 InetAddress::getAllByName；测试可注入假解析器验证重定向防护）。 */
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}

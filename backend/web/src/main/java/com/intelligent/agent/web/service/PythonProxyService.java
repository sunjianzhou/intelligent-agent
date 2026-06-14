package com.intelligent.agent.web.service;
import lombok.extern.slf4j.Slf4j;

import com.intelligent.agent.web.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.time.Instant;

/**
 * 统一管理 Java → Python Agent 的服务间 HTTP 代理：
 *   - token 自动刷新（临近过期 5 分钟时重新生成，避免长期运行后静默 401）
 *   - 封装 GET / POST / PUT / PATCH / DELETE 代理方法
 */
@Slf4j
@Service
public class PythonProxyService {

    private static final long REFRESH_BEFORE_SECS = 300;

    @Value("${intelligent-agent.python-service.base-url:http://localhost:8000}")
    private String baseUrl;

    @Autowired private JwtUtil jwtUtil;
    @Autowired private RestTemplate restTemplate;

    private volatile String serviceToken = null;
    private volatile Instant serviceTokenExpiry = Instant.MIN;

    public String getBaseUrl() {
        return baseUrl;
    }

    /** AgentService 等需要原始 token 字符串时调用（如 Apache HttpPost 直接设头）。*/
    public synchronized String getServiceToken() {
        if (serviceToken == null || Instant.now().isAfter(serviceTokenExpiry)) {
            serviceToken = jwtUtil.generateToken("java-service");
            Instant expiry = jwtUtil.parse(serviceToken).getExpiration().toInstant();
            serviceTokenExpiry = expiry.minusSeconds(REFRESH_BEFORE_SECS);
            log.debug("服务间 token 已刷新，下次刷新时间：{}", serviceTokenExpiry);
        }
        return serviceToken;
    }

    public HttpHeaders authHeaders() {
        return authHeaders(null);
    }

    /** 构建认证头；若 userId 非空且非 java-service，附加 X-User-Id 透传真实用户身份。 */
    public HttpHeaders authHeaders(String userId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Bearer " + getServiceToken());
        if (userId != null && !userId.isEmpty() && !"java-service".equals(userId)) {
            h.set("X-User-Id", userId);
        }
        return h;
    }

    public ResponseEntity<String> get(String path) {
        return restTemplate.exchange(
                baseUrl + path,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                String.class
        );
    }

    public ResponseEntity<String> get(String url, boolean absoluteUrl) {
        String target = absoluteUrl ? url : baseUrl + url;
        return restTemplate.exchange(target, HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class);
    }

    public ResponseEntity<String> post(String path, Object body) {
        return post(path, body, authHeaders());
    }

    public ResponseEntity<String> post(String path, Object body, HttpHeaders headers) {
        return restTemplate.exchange(
                baseUrl + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    public ResponseEntity<String> put(String path, Object body) {
        return restTemplate.exchange(
                baseUrl + path,
                HttpMethod.PUT,
                new HttpEntity<>(body, authHeaders()),
                String.class
        );
    }

    public ResponseEntity<String> patch(String path) {
        return restTemplate.exchange(
                baseUrl + path,
                HttpMethod.PATCH,
                new HttpEntity<>("{}", authHeaders()),
                String.class
        );
    }

    public ResponseEntity<String> patch(String path, String jsonBody) {
        return restTemplate.exchange(
                baseUrl + path,
                HttpMethod.PATCH,
                new HttpEntity<>(jsonBody, authHeaders()),
                String.class
        );
    }

    public ResponseEntity<String> delete(String path) {
        return restTemplate.exchange(
                baseUrl + path,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()),
                String.class
        );
    }

    // ── userId-aware 重载 ──────────────────────────────────────────────────────

    /** 从请求 Authorization 头提取真实用户 ID（前端 JWT 的 sub 字段）。*/
    public String extractUserIdFromRequest(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                io.jsonwebtoken.Claims c = jwtUtil.parse(auth.substring(7));
                String sub = c.getSubject();
                if (sub != null && !"java-service".equals(sub)) return sub;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public ResponseEntity<String> get(String path, String userId) {
        return restTemplate.exchange(baseUrl + path, HttpMethod.GET,
                new HttpEntity<>(authHeaders(userId)), String.class);
    }

    public ResponseEntity<String> get(String url, boolean absoluteUrl, String userId) {
        String target = absoluteUrl ? url : baseUrl + url;
        return restTemplate.exchange(target, HttpMethod.GET,
                new HttpEntity<>(authHeaders(userId)), String.class);
    }

    public ResponseEntity<String> post(String path, Object body, String userId) {
        return restTemplate.exchange(baseUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(userId)), String.class);
    }

    public ResponseEntity<String> put(String path, Object body, String userId) {
        return restTemplate.exchange(baseUrl + path, HttpMethod.PUT,
                new HttpEntity<>(body, authHeaders(userId)), String.class);
    }

    public ResponseEntity<String> patch(String path, String jsonBody, String userId) {
        return restTemplate.exchange(baseUrl + path, HttpMethod.PATCH,
                new HttpEntity<>(jsonBody, authHeaders(userId)), String.class);
    }

    public ResponseEntity<String> delete(String path, String userId) {
        return restTemplate.exchange(baseUrl + path, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(userId)), String.class);
    }

    /** 以 multipart/form-data 格式把文件转发到 Python。*/
    public ResponseEntity<String> postMultipart(
            String path,
            org.springframework.web.multipart.MultipartFile file,
            String description,
            String userId) throws java.io.IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", "Bearer " + getServiceToken());
        if (userId != null && !userId.isEmpty() && !"java-service".equals(userId)) {
            headers.set("X-User-Id", userId);
        }

        org.springframework.util.MultiValueMap<String, Object> body =
                new org.springframework.util.LinkedMultiValueMap<>();

        byte[] bytes = file.getBytes();
        String originalName = file.getOriginalFilename();
        org.springframework.core.io.ByteArrayResource resource =
                new org.springframework.core.io.ByteArrayResource(bytes) {
                    @Override public String getFilename() {
                        return originalName != null ? originalName : "upload";
                    }
                };
        body.add("file", resource);
        if (description != null && !description.isEmpty()) {
            body.add("description", description);
        }

        return restTemplate.exchange(
                baseUrl + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );
    }
}

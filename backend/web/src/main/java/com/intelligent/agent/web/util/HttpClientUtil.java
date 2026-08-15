package com.intelligent.agent.web.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 描述：
 *
 * @author lin miao
 * @date 2026/5/1
 */
@Component
public class HttpClientUtil {
    private static final Logger logger = LoggerFactory.getLogger(HttpClientUtil.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final CloseableHttpClient httpClient;

    public HttpClientUtil() {
        // J-03 闭环（2026-08-15）+ G7 统一 HTTP 栈（2026-08-15）：HttpClient 5 连接池
        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(50)
                        .setMaxConnPerRoute(20)
                        .build();
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(5000))
                .setResponseTimeout(Timeout.ofMilliseconds(30000))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(10000))
                .build();

        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(config)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();
    }

    public String get(String url) throws IOException {
        HttpGet httpGet = new HttpGet(url);
        try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
            HttpEntity entity = response.getEntity();
            return toString(entity);
        }
    }

    public String post(String url, Object data) throws IOException {
        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Content-Type", "application/json");

        if (data != null) {
            String json = objectMapper.writeValueAsString(data);
            httpPost.setEntity(new StringEntity(json, StandardCharsets.UTF_8));
        }

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            HttpEntity entity = response.getEntity();
            return toString(entity);
        }
    }

    private static String toString(HttpEntity entity) throws IOException {
        if (entity == null) {
            return null;
        }
        try {
            return EntityUtils.toString(entity);
        } catch (org.apache.hc.core5.http.ParseException e) {
            throw new IOException("解析 HTTP 响应失败: " + e.getMessage(), e);
        }
    }

    public <T> T get(String url, Class<T> clazz) throws IOException {
        String response = get(url);
        return response != null ? objectMapper.readValue(response, clazz) : null;
    }

    public <T> T post(String url, Object data, Class<T> clazz) throws IOException {
        String response = post(url, data);
        return response != null ? objectMapper.readValue(response, clazz) : null;
    }
}

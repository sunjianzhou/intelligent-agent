package com.intelligent.agent.web.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

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
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setSocketTimeout(30000)
                .build();

        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(config)
                .build();
    }

    public String get(String url) throws IOException {
        HttpGet httpGet = new HttpGet(url);
        try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
            HttpEntity entity = response.getEntity();
            return entity != null ? EntityUtils.toString(entity) : null;
        }
    }

    public String post(String url, Object data) throws IOException {
        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Content-Type", "application/json");

        if (data != null) {
            String json = objectMapper.writeValueAsString(data);
            httpPost.setEntity(new StringEntity(json, "UTF-8"));
        }

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            HttpEntity entity = response.getEntity();
            return entity != null ? EntityUtils.toString(entity) : null;
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

package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.service.PythonProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 所有 Python 代理 Controller 的基类。
 * 封装重复的 userId 提取、代理调用、异常处理和 fallback 逻辑。
 * 子类只需注入业务路由，无需重复样板代码。
 */
@Slf4j
public abstract class AbstractProxyController {

    @Autowired
    protected PythonProxyService proxy;

    @Autowired
    protected ObjectMapper objectMapper;

    // ── GET ──────────────────────────────────────────────────────────────────

    protected ResponseEntity<Map<String, Object>> proxyGet(
            String path, HttpServletRequest req) {
        return proxyGet(path, false, req, Collections.emptyMap());
    }

    protected ResponseEntity<Map<String, Object>> proxyGet(
            String path, HttpServletRequest req, Map<String, Object> fallbackData) {
        return proxyGet(path, false, req, fallbackData);
    }

    protected ResponseEntity<Map<String, Object>> proxyGetAbsolute(
            String url, HttpServletRequest req, Map<String, Object> fallbackData) {
        return proxyGet(url, true, req, fallbackData);
    }

    private ResponseEntity<Map<String, Object>> proxyGet(
            String url, boolean absolute, HttpServletRequest req,
            Map<String, Object> fallbackData) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = absolute
                    ? proxy.get(url, true, userId)
                    : proxy.get(url, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("GET {} 失败", url, e);
        }
        return ResponseEntity.ok(new HashMap<>(fallbackData));
    }

    // ── POST ─────────────────────────────────────────────────────────────────

    protected ResponseEntity<Map<String, Object>> proxyPost(
            String path, Object body, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post(path, body, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("POST {} 失败", path, e);
        }
        return errResponse();
    }

    protected ResponseEntity<Map<String, Object>> proxyPost(
            String path, String jsonBody, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post(path, jsonBody, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("POST {} 失败", path, e);
        }
        return errResponse();
    }

    // ── PUT ──────────────────────────────────────────────────────────────────

    protected ResponseEntity<Map<String, Object>> proxyPut(
            String path, Object body, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.put(path, body, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("PUT {} 失败", path, e);
        }
        return errResponse();
    }

    // ── PATCH ────────────────────────────────────────────────────────────────

    protected ResponseEntity<Map<String, Object>> proxyPatch(
            String path, Object body, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.patch(path, json, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("PATCH {} 失败", path, e);
        }
        return errResponse();
    }

    /** 用于 toggle 类接口：body 已是 JSON 字符串，无需二次序列化。*/
    protected ResponseEntity<Map<String, Object>> proxyPatch(
            String path, String jsonBody, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.patch(path, jsonBody, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("PATCH {} 失败", path, e);
        }
        return errResponse();
    }

    // ── DELETE ───────────────────────────────────────────────────────────────

    protected ResponseEntity<Map<String, Object>> proxyDelete(
            String path, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.delete(path, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("DELETE {} 失败", path, e);
        }
        return errResponse();
    }

    // ── 公共工具 ──────────────────────────────────────────────────────────────

    protected ResponseEntity<Map<String, Object>> errResponse() {
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }
}

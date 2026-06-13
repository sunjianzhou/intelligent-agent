package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.service.PythonProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 云端 LLM 服务商配置代理（转发到 Python Agent /api/cloud/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/cloud")
public class CloudProxyController {

    @Autowired private PythonProxyService proxy;
    @Autowired private ObjectMapper objectMapper;

    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> listProviders(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get("/api/cloud/providers", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取云端服务商列表失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("providers", Collections.emptyList());
        return ResponseEntity.ok(fallback);
    }

    @GetMapping("/presets")
    public ResponseEntity<Map<String, Object>> getPresets(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get("/api/cloud/presets", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取云端服务商预设失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("presets", Collections.emptyList());
        return ResponseEntity.ok(fallback);
    }

    @PostMapping("/providers")
    public ResponseEntity<Map<String, Object>> createProvider(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.post("/api/cloud/providers", json, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("创建云端服务商失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }

    @PutMapping("/providers/{pid}")
    public ResponseEntity<Map<String, Object>> updateProvider(
            @PathVariable String pid,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.put("/api/cloud/providers/" + pid, json, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("更新云端服务商失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }

    @DeleteMapping("/providers/{pid}")
    public ResponseEntity<Map<String, Object>> deleteProvider(
            @PathVariable String pid, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.delete("/api/cloud/providers/" + pid, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("删除云端服务商失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }

    @PostMapping("/providers/{pid}/activate")
    public ResponseEntity<Map<String, Object>> activateProvider(
            @PathVariable String pid, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post(
                    "/api/cloud/providers/" + pid + "/activate", "{}", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("激活云端服务商失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }

    @PostMapping("/deactivate")
    public ResponseEntity<Map<String, Object>> deactivate(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post("/api/cloud/deactivate", "{}", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("停用云端服务商失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }
}

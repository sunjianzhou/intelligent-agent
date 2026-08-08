package com.intelligent.agent.web.controller;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.service.AgentService;
import com.intelligent.agent.web.service.ModelService;
import com.intelligent.agent.web.service.PythonProxyService;
import com.intelligent.agent.web.infrastructure.scheduler.TaskSchedulerService;
import com.intelligent.agent.web.service.ConfigRuntimeService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查、系统信息、模型管理端点。
 * Python Agent 各业务代理已拆分到对应的 *ProxyController。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class HealthController {


    @Autowired private AgentService agentService;
    @Autowired private PythonProxyService proxy;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ModelService modelService;
    @Autowired(required = false) private TaskSchedulerService taskSchedulerService;
    @Autowired(required = false) private ConfigRuntimeService configRuntimeService;
    @Value("${ai.runtime.mode:python}")
    private String runtimeMode;

    // ── 健康检查 ──────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status",       "UP");
        health.put("service",      "intelligent-agent-backend");
        health.put("timestamp",    System.currentTimeMillis());
        health.put("java_version", System.getProperty("java.version"));
        return ResponseEntity.ok(health);
    }

    @GetMapping("/python/health")
    public ResponseEntity<Map<String, Object>> pythonHealth() {
        if ("java".equals(runtimeMode) || "shadow".equals(runtimeMode)) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "java-only");
            response.put("message", "Python Agent 已退役（2026-08-08），Java 后端自包含运行");
            response.put("java_version", System.getProperty("java.version"));
            return ResponseEntity.ok(response);
        }
        Map<String, Object> response = new HashMap<>();
        try {
            ResponseEntity<String> res = proxy.get("/health");
            response.put("status",         "connected");
            response.put("python_service", res.getBody());
            response.put("url",            proxy.getBaseUrl());
            response.put("http_status",    res.getStatusCodeValue());
        } catch (ResourceAccessException e) {
            response.put("status",  "disconnected");
            response.put("error",   e.getMessage());
            response.put("url",     proxy.getBaseUrl());
            response.put("message", "请确保 Python 服务在 " + proxy.getBaseUrl() + " 上运行");
        } catch (Exception e) {
            response.put("status", "error");
            response.put("error",  e.getMessage());
            response.put("url",    proxy.getBaseUrl());
        }
        return ResponseEntity.ok(response);
    }

    // ── 系统信息 ──────────────────────────────────────────────

    @GetMapping("/system/info")
    public ResponseEntity<Map<String, Object>> systemInfo() {
        return ResponseEntity.ok(agentService.getRealSystemInfo());
    }

    @GetMapping("/system/resources")
    public ResponseEntity<Map<String, Object>> systemResources() {
        try {
            ResponseEntity<String> res = proxy.get("/api/system/resources");
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(agentService.getObjectMapper().readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取资源监控失败", e);
        }
        return ResponseEntity.ok(new HashMap<>());
    }

    // ── 模型管理 ──────────────────────────────────────────────

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> models(HttpServletRequest req) {
        if ("java".equals(runtimeMode) || "shadow".equals(runtimeMode)) {
            return ResponseEntity.ok(modelService.getModels(
                    proxy != null ? proxy.extractUserIdFromRequest(req) : null));
        }
        String userId = proxy.extractUserIdFromRequest(req);
        return ResponseEntity.ok(agentService.getModels(userId));
    }

    // ── 运行时资源配置 ──────────────────────────────────────

    @GetMapping("/config/runtime")
    public ResponseEntity<Map<String, Object>> getRuntimeConfig() {
        if (configRuntimeService != null
                && ("java".equals(runtimeMode) || "shadow".equals(runtimeMode))) {
            return ResponseEntity.ok(configRuntimeService.get());
        }
        try {
            ResponseEntity<String> res = proxy.get("/api/config/runtime");
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(agentService.getObjectMapper().readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取运行时配置失败", e);
        }
        return ResponseEntity.ok(new HashMap<>());
    }

    @PatchMapping("/config/runtime")
    public ResponseEntity<Map<String, Object>> patchRuntimeConfig(@RequestBody Map<String, Object> body) {
        if (configRuntimeService != null
                && ("java".equals(runtimeMode) || "shadow".equals(runtimeMode))) {
            return ResponseEntity.ok(configRuntimeService.patch(body));
        }
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.patch("/api/config/runtime", json);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(agentService.getObjectMapper().readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("更新运行时配置失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false); err.put("message", "更新失败");
        return ResponseEntity.ok(err);
    }

    /** 定时任务通知轮询代理：前端每30s调用一次，取出并返回待推送通知 */
    @GetMapping("/notifications/poll")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> pollNotifications() {
        if (taskSchedulerService != null
                && ("java".equals(runtimeMode) || "shadow".equals(runtimeMode))) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("notifications", taskSchedulerService.pollNotifications());
            empty.put("count", ((java.util.List<?>) empty.get("notifications")).size());
            return ResponseEntity.ok(empty);
        }
        try {
            ResponseEntity<String> res = proxy.get("/api/notifications/poll");
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(agentService.getObjectMapper().readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.debug("通知轮询失败: {}", e.getMessage());
        }
        Map<String, Object> empty = new HashMap<>();
        empty.put("notifications", java.util.Collections.emptyList());
        empty.put("count", 0);
        return ResponseEntity.ok(empty);
    }

    @PostMapping("/model/switch")
    public ResponseEntity<Map<String, Object>> switchModel(@RequestBody Map<String, String> body,
                                                           HttpServletRequest req) {
        String modelName = body.get("model");
        if (modelName == null || StringUtils.isBlank(modelName)) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "model 参数不能为空");
            return ResponseEntity.badRequest().body(err);
        }
        if ("java".equals(runtimeMode) || "shadow".equals(runtimeMode)) {
            return ResponseEntity.ok(modelService.switchModel(
                    proxy != null ? proxy.extractUserIdFromRequest(req) : null, modelName));
        }
        String userId = proxy.extractUserIdFromRequest(req);
        return ResponseEntity.ok(agentService.switchModel(modelName, userId));
    }
}

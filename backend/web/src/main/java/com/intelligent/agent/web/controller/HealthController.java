package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;

import com.intelligent.agent.web.service.AgentService;
import com.intelligent.agent.web.service.ModelService;
import com.intelligent.agent.web.infrastructure.scheduler.TaskSchedulerService;
import com.intelligent.agent.web.infrastructure.monitoring.SystemResourceService;
import com.intelligent.agent.web.service.ConfigRuntimeService;
import com.intelligent.agent.web.ai.llm.circuit.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查、系统信息、模型管理端点（Java-only）。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class HealthController {

    @Autowired private AgentService agentService;
    @Autowired private ModelService modelService;
    @Autowired(required = false) private TaskSchedulerService taskSchedulerService;
    @Autowired(required = false) private SystemResourceService systemResourceService;
    @Autowired(required = false) private ConfigRuntimeService configRuntimeService;
    @Autowired(required = false) private CircuitBreakerRegistry circuitBreakerRegistry;

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

    /** 历史兼容端点：Python 已退役，始终返回自包含的 java-only 状态。 */
    @GetMapping("/python/health")
    public ResponseEntity<Map<String, Object>> pythonHealth() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "java-only");
        response.put("message", "Python Agent 已退役（2026-08-08），Java 后端自包含运行");
        response.put("java_version", System.getProperty("java.version"));
        return ResponseEntity.ok(response);
    }

    // ── 系统信息 ──────────────────────────────────────────────

    @GetMapping("/system/info")
    public ResponseEntity<Map<String, Object>> systemInfo() {
        return ResponseEntity.ok(agentService.getRealSystemInfo());
    }

    @GetMapping("/system/resources")
    public ResponseEntity<Map<String, Object>> systemResources() {
        if (systemResourceService != null) {
            return ResponseEntity.ok(systemResourceService.get());
        }
        return ResponseEntity.ok(new HashMap<>());
    }

    /** G6：LLM 熔断器 + SLO 状态（按模型：state / success_rate / 拒绝数等）。 */
    @GetMapping("/llm/status")
    public ResponseEntity<Map<String, Object>> llmStatus() {
        if (circuitBreakerRegistry != null) {
            return ResponseEntity.ok(circuitBreakerRegistry.status());
        }
        return ResponseEntity.ok(Map.of("enabled", false, "breakers", java.util.List.of()));
    }

    // ── 模型管理 ──────────────────────────────────────────────

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> models(HttpServletRequest req) {
        return ResponseEntity.ok(modelService.getModels(UserContext.userId(req)));
    }

    @PostMapping("/model/switch")
    public ResponseEntity<Map<String, Object>> switchModel(
            @RequestBody Map<String, String> body, HttpServletRequest req) {
        String modelName = body == null ? null : body.get("model");
        return ResponseEntity.ok(modelService.switchModel(UserContext.userId(req), modelName));
    }

    // ── 运行时资源配置 ──────────────────────────────────────

    @GetMapping("/config/runtime")
    public ResponseEntity<Map<String, Object>> getRuntimeConfig() {
        if (configRuntimeService != null) {
            return ResponseEntity.ok(configRuntimeService.get());
        }
        return ResponseEntity.ok(new HashMap<>());
    }

    @PatchMapping("/config/runtime")
    public ResponseEntity<Map<String, Object>> patchRuntimeConfig(
            @RequestBody Map<String, Object> body) {
        if (configRuntimeService != null) {
            return ResponseEntity.ok(configRuntimeService.patch(body));
        }
        return ResponseEntity.ok(Map.of("success", false, "message", "运行时配置不可用"));
    }

    /** 定时任务通知轮询：取出并返回待推送通知（本地调度队列）。 */
    @GetMapping("/notifications/poll")
    public ResponseEntity<Map<String, Object>> pollNotifications() {
        Map<String, Object> result = new HashMap<>();
        if (taskSchedulerService != null) {
            java.util.List<Map<String, Object>> notifications =
                    taskSchedulerService.pollNotifications();
            result.put("notifications", notifications);
            result.put("count", notifications.size());
        } else {
            result.put("notifications", java.util.Collections.emptyList());
            result.put("count", 0);
        }
        return ResponseEntity.ok(result);
    }
}

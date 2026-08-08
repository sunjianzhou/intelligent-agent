package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import com.intelligent.agent.web.service.CloudService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;

/**
 * 云端 LLM 服务商配置端点（TODO-110 Task 2 本地化；python 模式回退代理）。
 */
@Slf4j
@RestController
@RequestMapping("/api/cloud")
public class CloudProxyController extends AbstractProxyController {

    private final CloudService cloudService;
    private final String runtimeMode;

    public CloudProxyController(CloudService cloudService,
                                @Value("${ai.runtime.mode:python}") String runtimeMode) {
        this.cloudService = cloudService;
        this.runtimeMode = runtimeMode;
    }

    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> listProviders(HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(cloudService.listProviders());
        }
        return proxyGet("/api/cloud/providers", req,
                Collections.singletonMap("providers", Collections.emptyList()));
    }

    @GetMapping("/presets")
    public ResponseEntity<Map<String, Object>> getPresets(HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(cloudService.presets());
        }
        return proxyGet("/api/cloud/presets", req,
                Collections.singletonMap("presets", Collections.emptyList()));
    }

    @PostMapping("/providers")
    public ResponseEntity<Map<String, Object>> createProvider(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(cloudService.createProvider(body));
        }
        return proxyPost("/api/cloud/providers", body, req);
    }

    @PutMapping("/providers/{pid}")
    public ResponseEntity<Map<String, Object>> updateProvider(
            @PathVariable String pid,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(cloudService.updateProvider(pid, body));
        }
        return proxyPut("/api/cloud/providers/" + pid, body, req);
    }

    @DeleteMapping("/providers/{pid}")
    public ResponseEntity<Map<String, Object>> deleteProvider(
            @PathVariable String pid, HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(cloudService.deleteProvider(pid));
        }
        return proxyDelete("/api/cloud/providers/" + pid, req);
    }

    @PostMapping("/providers/{pid}/activate")
    public ResponseEntity<Map<String, Object>> activateProvider(
            @PathVariable String pid, HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(cloudService.activate(pid));
        }
        return proxyPost("/api/cloud/providers/" + pid + "/activate", "{}", req);
    }

    @PostMapping("/deactivate")
    public ResponseEntity<Map<String, Object>> deactivate(HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(cloudService.deactivate());
        }
        return proxyPost("/api/cloud/deactivate", "{}", req);
    }

    private boolean localRuntime() {
        return "java".equals(runtimeMode) || "shadow".equals(runtimeMode);
    }
}

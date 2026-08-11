package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import com.intelligent.agent.web.service.CloudService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 云端 LLM 服务商配置端点（本地 {@link CloudService}）。
 */
@Slf4j
@RestController
@RequestMapping("/api/cloud")
public class CloudProxyController {

    private final CloudService cloudService;

    public CloudProxyController(CloudService cloudService) {
        this.cloudService = cloudService;
    }

    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> listProviders() {
        return ResponseEntity.ok(cloudService.listProviders());
    }

    @GetMapping("/presets")
    public ResponseEntity<Map<String, Object>> getPresets() {
        return ResponseEntity.ok(cloudService.presets());
    }

    @PostMapping("/providers")
    public ResponseEntity<Map<String, Object>> createProvider(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(cloudService.createProvider(body));
    }

    @PutMapping("/providers/{pid}")
    public ResponseEntity<Map<String, Object>> updateProvider(
            @PathVariable String pid, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(cloudService.updateProvider(pid, body));
    }

    @DeleteMapping("/providers/{pid}")
    public ResponseEntity<Map<String, Object>> deleteProvider(@PathVariable String pid) {
        return ResponseEntity.ok(cloudService.deleteProvider(pid));
    }

    @PostMapping("/providers/{pid}/activate")
    public ResponseEntity<Map<String, Object>> activateProvider(@PathVariable String pid) {
        return ResponseEntity.ok(cloudService.activate(pid));
    }

    @PostMapping("/deactivate")
    public ResponseEntity<Map<String, Object>> deactivate() {
        return ResponseEntity.ok(cloudService.deactivate());
    }
}

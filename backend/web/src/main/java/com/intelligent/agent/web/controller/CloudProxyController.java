package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;

/**
 * 云端 LLM 服务商配置代理（转发到 Python Agent /api/cloud/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/cloud")
public class CloudProxyController extends AbstractProxyController {

    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> listProviders(HttpServletRequest req) {
        return proxyGet("/api/cloud/providers", req,
                Collections.singletonMap("providers", Collections.emptyList()));
    }

    @GetMapping("/presets")
    public ResponseEntity<Map<String, Object>> getPresets(HttpServletRequest req) {
        return proxyGet("/api/cloud/presets", req,
                Collections.singletonMap("presets", Collections.emptyList()));
    }

    @PostMapping("/providers")
    public ResponseEntity<Map<String, Object>> createProvider(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPost("/api/cloud/providers", body, req);
    }

    @PutMapping("/providers/{pid}")
    public ResponseEntity<Map<String, Object>> updateProvider(
            @PathVariable String pid,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        return proxyPut("/api/cloud/providers/" + pid, body, req);
    }

    @DeleteMapping("/providers/{pid}")
    public ResponseEntity<Map<String, Object>> deleteProvider(
            @PathVariable String pid, HttpServletRequest req) {
        return proxyDelete("/api/cloud/providers/" + pid, req);
    }

    @PostMapping("/providers/{pid}/activate")
    public ResponseEntity<Map<String, Object>> activateProvider(
            @PathVariable String pid, HttpServletRequest req) {
        return proxyPost("/api/cloud/providers/" + pid + "/activate", "{}", req);
    }

    @PostMapping("/deactivate")
    public ResponseEntity<Map<String, Object>> deactivate(HttpServletRequest req) {
        return proxyPost("/api/cloud/deactivate", "{}", req);
    }
}

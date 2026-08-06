package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具列表代理端点（转发到 Python Agent /api/tools/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/tools")
public class ToolProxyController extends AbstractProxyController {

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> toolsList(HttpServletRequest req) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("tools", Collections.emptyList());
        fallback.put("count", 0);
        return proxyGet("/api/tools/list", req, fallback);
    }
}

@RestController
@RequestMapping("/api/config")
class ConfigProxyController extends AbstractProxyController {

    @PatchMapping("/env")
    public ResponseEntity<Map<String, Object>> updateEnv(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPatch("/api/config/env", body, req);
    }

    @PatchMapping("/params")
    public ResponseEntity<Map<String, Object>> updateParams(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPatch("/api/config/params", body, req);
    }

    @GetMapping("/database")
    public ResponseEntity<Map<String, Object>> getDatabaseConfig(HttpServletRequest req) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("config", new HashMap<>());
        fallback.put("connected", false);
        return proxyGet("/api/config/database", req, fallback);
    }

    @PutMapping("/database")
    public ResponseEntity<Map<String, Object>> updateDatabaseConfig(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPut("/api/config/database", body, req);
    }
}

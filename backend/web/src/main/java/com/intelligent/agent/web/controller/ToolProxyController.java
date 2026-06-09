package com.intelligent.agent.web.controller;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.service.PythonProxyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具列表代理端点（转发到 Python Agent /api/tools/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/tools")
public class ToolProxyController {


    @Autowired private PythonProxyService proxy;
    @Autowired private ObjectMapper objectMapper;

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> toolsList(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get("/api/tools/list", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取工具列表失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("tools", new ArrayList<>());
        fallback.put("count", 0);
        return ResponseEntity.ok(fallback);
    }
}

@RestController
@RequestMapping("/api/config")
class ConfigProxyController {
    @Autowired private PythonProxyService proxy;
    @Autowired private ObjectMapper objectMapper;

    @PatchMapping("/env")
    public ResponseEntity<Map<String, Object>> updateEnv(@RequestBody Map<String, String> body,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.patch("/api/config/env", json, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) { /* ignore */ }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }

    @PatchMapping("/params")
    public ResponseEntity<Map<String, Object>> updateParams(@RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String json = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = proxy.patch("/api/config/params", json, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) { /* ignore */ }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }
}

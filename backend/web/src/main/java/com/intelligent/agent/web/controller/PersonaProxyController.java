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
 * Persona 管理代理端点（转发到 Python Agent /api/personas/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/personas")
public class PersonaProxyController {

    @Autowired private PythonProxyService proxy;
    @Autowired private ObjectMapper objectMapper;

    @GetMapping("")
    public ResponseEntity<Map<String, Object>> listPersonas(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get("/api/personas", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取 Persona 列表失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("personas", new ArrayList<>());
        fallback.put("count", 0);
        return ResponseEntity.ok(fallback);
    }

    @GetMapping("/current")
    public ResponseEntity<Map<String, Object>> getCurrentPersona(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get("/api/personas/current", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取当前 Persona 失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("persona", "default");
        return ResponseEntity.ok(fallback);
    }

    @PostMapping("/switch")
    public ResponseEntity<Map<String, Object>> switchPersona(@RequestBody Map<String, Object> body,
                                                              HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post("/api/personas/switch", body, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("切换 Persona 失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "切换角色失败");
        return ResponseEntity.ok(err);
    }

    @PostMapping("/upsert")
    public ResponseEntity<Map<String, Object>> upsertPersona(@RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post("/api/personas/upsert", body, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("保存 Persona 失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "保存角色失败");
        return ResponseEntity.ok(err);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> deletePersona(@PathVariable String name,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.delete("/api/personas/" + name, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("删除 Persona 失败: {}", name, e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "删除角色失败");
        return ResponseEntity.ok(err);
    }

    @GetMapping("/{name}/content")
    public ResponseEntity<Map<String, Object>> getPersonaContent(@PathVariable String name,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get("/api/personas/" + name + "/content", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取 Persona 内容失败: {}", name, e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }
}

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
import java.util.List;
import java.util.Map;

/**
 * Skill 管理代理端点（转发到 Python Agent /api/skills/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/skills")
public class SkillProxyController {


    @Autowired private PythonProxyService proxy;
    @Autowired private ObjectMapper objectMapper;

    @GetMapping("")
    public ResponseEntity<Map<String, Object>> listSkills(
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "false") boolean enabled_only,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            String url = proxy.getBaseUrl() + "/api/skills?enabled_only=" + enabled_only
                    + (tag != null ? "&tag=" + tag : "");
            ResponseEntity<String> res = proxy.get(url, true, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取 Skill 列表失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("skills", new ArrayList<>());
        return ResponseEntity.ok(fallback);
    }

    @PostMapping("")
    public ResponseEntity<Map<String, Object>> createSkill(@RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post("/api/skills", body, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("创建 Skill 失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }

    @PutMapping("/{skillId}")
    public ResponseEntity<Map<String, Object>> updateSkill(
            @PathVariable String skillId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.put("/api/skills/" + skillId, body, userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("更新 Skill 失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }

    @DeleteMapping("/{skillId}")
    public ResponseEntity<Map<String, Object>> deleteSkill(@PathVariable String skillId,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            proxy.delete("/api/skills/" + skillId, userId);
            Map<String, Object> r = new HashMap<>();
            r.put("success", true);
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            log.error("删除 Skill 失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }

    @PatchMapping("/{skillId}/toggle")
    public ResponseEntity<Map<String, Object>> toggleSkill(@PathVariable String skillId,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.patch("/api/skills/" + skillId + "/toggle", "{}", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("切换 Skill 失败", e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return ResponseEntity.ok(err);
    }

    // ── Skill 模板 ────────────────────────────────────────────

    @GetMapping("/templates/list")
    public ResponseEntity<Map<String, Object>> listTemplates(HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get("/api/skills/templates/list", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("获取 Skill 模板列表失败", e);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("templates", new ArrayList<>());
        fallback.put("count", 0);
        return ResponseEntity.ok(fallback);
    }

    @PostMapping("/templates/{templateId}/apply")
    public ResponseEntity<Map<String, Object>> applyTemplate(@PathVariable String templateId,
            HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post("/api/skills/templates/" + templateId + "/apply", "{}", userId);
            if (res.getStatusCode().is2xxSuccessful())
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
        } catch (Exception e) {
            log.error("应用 Skill 模板失败: {}", templateId, e);
        }
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", "应用模板失败");
        return ResponseEntity.ok(err);
    }
}

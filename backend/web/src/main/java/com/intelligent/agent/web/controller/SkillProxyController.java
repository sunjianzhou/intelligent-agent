package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.domain.skill.SkillService;
import com.intelligent.agent.web.service.PythonProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Skill 管理端点。
 * <ul>
 *   <li>java / shadow 运行时：走本地 {@link SkillService}；</li>
 *   <li>python 运行时：转发到 Python Agent /api/skills/*。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/skills")
public class SkillProxyController {

    private final PythonProxyService proxy;
    private final ObjectMapper objectMapper;
    private final SkillService skillService;
    private final String runtimeMode;

    public SkillProxyController(PythonProxyService proxy,
                                ObjectMapper objectMapper,
                                SkillService skillService,
                                @Value("${ai.runtime.mode:python}") String runtimeMode) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
        this.skillService = skillService;
        this.runtimeMode = runtimeMode;
    }

    @GetMapping("")
    public ResponseEntity<Map<String, Object>> listSkills(
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "false") boolean enabled_only,
            HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(skillService.listSkills(tag, enabled_only));
        }
        String url = proxy.getBaseUrl() + "/api/skills?enabled_only=" + enabled_only
                + (tag != null ? "&tag=" + tag : "");
        return proxyGet(url, req, Collections.singletonMap("skills", Collections.emptyList()));
    }

    @PostMapping("")
    public ResponseEntity<Map<String, Object>> createSkill(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(skillService.createSkill(body));
        }
        return proxyPost("/api/skills", body, req);
    }

    @PutMapping("/{skillId}")
    public ResponseEntity<Map<String, Object>> updateSkill(
            @PathVariable String skillId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(skillService.updateSkill(skillId, body));
        }
        return proxyPut("/api/skills/" + skillId, body, req);
    }

    @DeleteMapping("/{skillId}")
    public ResponseEntity<Map<String, Object>> deleteSkill(
            @PathVariable String skillId, HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(skillService.deleteSkill(skillId));
        }
        return proxyDelete("/api/skills/" + skillId, req);
    }

    @PatchMapping("/{skillId}/toggle")
    public ResponseEntity<Map<String, Object>> toggleSkill(
            @PathVariable String skillId, HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(skillService.toggleSkill(skillId));
        }
        return proxyPatch("/api/skills/" + skillId + "/toggle", "{}", req);
    }

    @GetMapping("/templates/list")
    public ResponseEntity<Map<String, Object>> listTemplates(HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(skillService.templates());
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("templates", Collections.emptyList());
        fallback.put("count", 0);
        return proxyGet("/api/skills/templates/list", req, fallback);
    }

    @PostMapping("/templates/{templateId}/apply")
    public ResponseEntity<Map<String, Object>> applyTemplate(
            @PathVariable String templateId, HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(skillService.applyTemplate(templateId));
        }
        return proxyPost("/api/skills/templates/" + templateId + "/apply", "{}", req);
    }

    private boolean localRuntime() {
        return "java".equals(runtimeMode) || "shadow".equals(runtimeMode);
    }

    private ResponseEntity<Map<String, Object>> proxyGet(String path, HttpServletRequest req,
                                                         Map<String, Object> fallback) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get(path, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("GET {} 失败", path, e);
        }
        return ResponseEntity.ok(new HashMap<>(fallback));
    }

    private ResponseEntity<Map<String, Object>> proxyPost(String path, Object body, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.post(path, body, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("POST {} 失败", path, e);
        }
        return ResponseEntity.ok(errResponse());
    }

    private ResponseEntity<Map<String, Object>> proxyPut(String path, Object body, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.put(path, body, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("PUT {} 失败", path, e);
        }
        return ResponseEntity.ok(errResponse());
    }

    private ResponseEntity<Map<String, Object>> proxyPatch(String path, String jsonBody, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.patch(path, jsonBody, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("PATCH {} 失败", path, e);
        }
        return ResponseEntity.ok(errResponse());
    }

    private ResponseEntity<Map<String, Object>> proxyDelete(String path, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.delete(path, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("DELETE {} 失败", path, e);
        }
        return ResponseEntity.ok(errResponse());
    }

    private static Map<String, Object> errResponse() {
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return err;
    }
}

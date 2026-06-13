package com.intelligent.agent.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Skill 管理代理端点（转发到 Python Agent /api/skills/*）。
 */
@Slf4j
@RestController
@RequestMapping("/api/skills")
public class SkillProxyController extends AbstractProxyController {

    @GetMapping("")
    public ResponseEntity<Map<String, Object>> listSkills(
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "false") boolean enabled_only,
            HttpServletRequest req) {
        String url = proxy.getBaseUrl() + "/api/skills?enabled_only=" + enabled_only
                + (tag != null ? "&tag=" + tag : "");
        return proxyGetAbsolute(url, req,
                Collections.singletonMap("skills", Collections.emptyList()));
    }

    @PostMapping("")
    public ResponseEntity<Map<String, Object>> createSkill(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return proxyPost("/api/skills", body, req);
    }

    @PutMapping("/{skillId}")
    public ResponseEntity<Map<String, Object>> updateSkill(
            @PathVariable String skillId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        return proxyPut("/api/skills/" + skillId, body, req);
    }

    @DeleteMapping("/{skillId}")
    public ResponseEntity<Map<String, Object>> deleteSkill(
            @PathVariable String skillId, HttpServletRequest req) {
        return proxyDelete("/api/skills/" + skillId, req);
    }

    @PatchMapping("/{skillId}/toggle")
    public ResponseEntity<Map<String, Object>> toggleSkill(
            @PathVariable String skillId, HttpServletRequest req) {
        return proxyPatch("/api/skills/" + skillId + "/toggle", "{}", req);
    }

    @GetMapping("/templates/list")
    public ResponseEntity<Map<String, Object>> listTemplates(HttpServletRequest req) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("templates", Collections.emptyList());
        fallback.put("count", 0);
        return proxyGet("/api/skills/templates/list", req, fallback);
    }

    @PostMapping("/templates/{templateId}/apply")
    public ResponseEntity<Map<String, Object>> applyTemplate(
            @PathVariable String templateId, HttpServletRequest req) {
        return proxyPost("/api/skills/templates/" + templateId + "/apply", "{}", req);
    }
}

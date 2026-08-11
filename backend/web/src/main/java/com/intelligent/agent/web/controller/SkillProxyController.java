package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.domain.skill.SkillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Skill 管理端点（本地 {@link SkillService}）。
 */
@Slf4j
@RestController
@RequestMapping("/api/skills")
public class SkillProxyController {

    private final SkillService skillService;

    public SkillProxyController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping("")
    public ResponseEntity<Map<String, Object>> listSkills(
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "false") boolean enabled_only) {
        return ResponseEntity.ok(skillService.listSkills(tag, enabled_only));
    }

    @PostMapping("")
    public ResponseEntity<Map<String, Object>> createSkill(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(skillService.createSkill(body));
    }

    @PutMapping("/{skillId}")
    public ResponseEntity<Map<String, Object>> updateSkill(
            @PathVariable String skillId, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(skillService.updateSkill(skillId, body));
    }

    @DeleteMapping("/{skillId}")
    public ResponseEntity<Map<String, Object>> deleteSkill(@PathVariable String skillId) {
        return ResponseEntity.ok(skillService.deleteSkill(skillId));
    }

    @PatchMapping("/{skillId}/toggle")
    public ResponseEntity<Map<String, Object>> toggleSkill(@PathVariable String skillId) {
        return ResponseEntity.ok(skillService.toggleSkill(skillId));
    }

    @GetMapping("/templates/list")
    public ResponseEntity<Map<String, Object>> listTemplates() {
        return ResponseEntity.ok(skillService.templates());
    }

    @PostMapping("/templates/{templateId}/apply")
    public ResponseEntity<Map<String, Object>> applyTemplate(@PathVariable String templateId) {
        return ResponseEntity.ok(skillService.applyTemplate(templateId));
    }
}

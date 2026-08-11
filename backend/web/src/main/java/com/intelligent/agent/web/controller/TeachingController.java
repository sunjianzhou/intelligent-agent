package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.domain.teaching.TeachingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 教学体系端点（本地 {@link TeachingService}）。
 */
@Slf4j
@RestController
@RequestMapping("/api/teaching")
public class TeachingController {

    private final TeachingService teachingService;

    public TeachingController(TeachingService teachingService) {
        this.teachingService = teachingService;
    }

    @GetMapping("/daily-plan")
    public ResponseEntity<Map<String, Object>> dailyPlan(
            @RequestParam(defaultValue = "k8s") String topic) {
        return ResponseEntity.ok(teachingService.dailyPlan(topic));
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(teachingService.submit(
                str(body.get("user_id")),
                str(body.getOrDefault("topic", "k8s")),
                answerList(body.get("answers"))));
    }

    @GetMapping("/wrong-book")
    public ResponseEntity<Map<String, Object>> wrongBook(
            @RequestParam(defaultValue = "k8s") String topic,
            @RequestParam(defaultValue = "false") boolean include_resolved) {
        return ResponseEntity.ok(teachingService.wrongBook(topic, include_resolved));
    }

    @PostMapping("/wrong-book/{questionId}/resolve")
    public ResponseEntity<Map<String, Object>> resolveWrong(
            @PathVariable String questionId,
            @RequestParam(defaultValue = "k8s") String topic) {
        return ResponseEntity.ok(teachingService.resolveWrong(questionId, topic));
    }

    @GetMapping("/command-log")
    public ResponseEntity<Map<String, Object>> commandLog(
            @RequestParam(defaultValue = "k8s") String topic) {
        return ResponseEntity.ok(teachingService.commandLog(topic));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> answerList(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        List<Map<String, Object>> answers = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof Map) {
                answers.add((Map<String, Object>) item);
            }
        }
        return answers;
    }
}

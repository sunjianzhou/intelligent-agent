package com.intelligent.agent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.domain.teaching.TeachingService;
import com.intelligent.agent.web.service.PythonProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 教学体系端点（/api/teaching/*）。
 * <ul>
 *   <li>java / shadow 运行时：走本地 {@link TeachingService}；</li>
 *   <li>python 运行时：转发到 Python Agent /api/teaching/*。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/teaching")
public class TeachingController {

    private final PythonProxyService proxy;
    private final ObjectMapper objectMapper;
    private final TeachingService teachingService;
    private final String runtimeMode;

    public TeachingController(PythonProxyService proxy,
                              ObjectMapper objectMapper,
                              TeachingService teachingService,
                              @Value("${ai.runtime.mode:python}") String runtimeMode) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
        this.teachingService = teachingService;
        this.runtimeMode = runtimeMode;
    }

    @GetMapping("/daily-plan")
    public ResponseEntity<Map<String, Object>> dailyPlan(
            @RequestParam(defaultValue = "k8s") String topic, HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(teachingService.dailyPlan(topic));
        }
        return proxyGet("/api/teaching/daily-plan?topic=" + topic, req);
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(teachingService.submit(
                    str(body.get("user_id")),
                    str(body.getOrDefault("topic", "k8s")),
                    answerList(body.get("answers"))));
        }
        return proxyPost("/api/teaching/submit", body, req);
    }

    @GetMapping("/wrong-book")
    public ResponseEntity<Map<String, Object>> wrongBook(
            @RequestParam(defaultValue = "k8s") String topic,
            @RequestParam(defaultValue = "false") boolean include_resolved,
            HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(teachingService.wrongBook(topic, include_resolved));
        }
        return proxyGet("/api/teaching/wrong-book?topic=" + topic
                + "&include_resolved=" + include_resolved, req);
    }

    @PostMapping("/wrong-book/{questionId}/resolve")
    public ResponseEntity<Map<String, Object>> resolveWrong(
            @PathVariable String questionId,
            @RequestParam(defaultValue = "k8s") String topic,
            HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(teachingService.resolveWrong(questionId, topic));
        }
        return proxyPost("/api/teaching/wrong-book/" + questionId + "/resolve?topic=" + topic,
                "{}", req);
    }

    @GetMapping("/command-log")
    public ResponseEntity<Map<String, Object>> commandLog(
            @RequestParam(defaultValue = "k8s") String topic, HttpServletRequest req) {
        if (localRuntime()) {
            return ResponseEntity.ok(teachingService.commandLog(topic));
        }
        return proxyGet("/api/teaching/command-log?topic=" + topic, req);
    }

    private boolean localRuntime() {
        return "java".equals(runtimeMode) || "shadow".equals(runtimeMode);
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

    private ResponseEntity<Map<String, Object>> proxyGet(String path, HttpServletRequest req) {
        String userId = proxy.extractUserIdFromRequest(req);
        try {
            ResponseEntity<String> res = proxy.get(path, userId);
            if (res.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok(objectMapper.readValue(res.getBody(), Map.class));
            }
        } catch (Exception e) {
            log.error("GET {} 失败", path, e);
        }
        return ResponseEntity.ok(errResponse());
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

    private static Map<String, Object> errResponse() {
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        return err;
    }
}

package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.infrastructure.observability.TraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行追踪端点（G4）：list / get / delete，按 userId 隔离。
 */
@Slf4j
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceService traceService;

    public TraceController(TraceService traceService) {
        this.traceService = traceService;
    }

    @GetMapping("")
    public ResponseEntity<Map<String, Object>> listTraces(
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest req) {
        List<Map<String, Object>> traces = traceService.list(
                UserContext.userId(req), Math.max(1, Math.min(limit, 500)));
        Map<String, Object> result = new HashMap<>();
        result.put("traces", traces);
        result.put("count", traces.size());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<Map<String, Object>> getTrace(
            @PathVariable String requestId, HttpServletRequest req) {
        Map<String, Object> trace = traceService.get(UserContext.userId(req), requestId);
        if (trace == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "message", "trace 不存在或无权访问: " + requestId));
        }
        return ResponseEntity.ok(trace);
    }

    @DeleteMapping("/{requestId}")
    public ResponseEntity<Map<String, Object>> deleteTrace(
            @PathVariable String requestId, HttpServletRequest req) {
        boolean ok = traceService.delete(UserContext.userId(req), requestId);
        return ResponseEntity.ok(Map.of(
                "success", ok,
                "message", ok ? "trace 已删除" : "trace 不存在或无权访问"));
    }
}

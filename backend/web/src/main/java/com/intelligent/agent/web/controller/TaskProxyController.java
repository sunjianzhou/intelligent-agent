package com.intelligent.agent.web.controller;

import com.intelligent.agent.web.domain.task.TaskService;
import com.intelligent.agent.web.infrastructure.scheduler.TaskSchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 任务调度端点（本地 {@link TaskService}）。
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
public class TaskProxyController {

    private final TaskService taskService;
    private final TaskSchedulerService scheduler;

    public TaskProxyController(TaskService taskService) {
        this(taskService, null);
    }

    @Autowired
    public TaskProxyController(TaskService taskService, TaskSchedulerService scheduler) {
        this.taskService = taskService;
        this.scheduler = scheduler;
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> tasksList(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return ok(taskService.listTasks(status, limit));
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> resp = ok(taskService.createTask(body));
        notifyScheduler();
        return resp;
    }

    @PatchMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> updateTask(
            @PathVariable String taskId, @RequestBody Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> resp = ok(taskService.updateTask(taskId, body));
        notifyScheduler();
        return resp;
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> deleteTask(@PathVariable String taskId) {
        ResponseEntity<Map<String, Object>> resp = ok(taskService.deleteTask(taskId));
        notifyScheduler();
        return resp;
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelTask(@PathVariable String taskId) {
        ResponseEntity<Map<String, Object>> resp = ok(taskService.cancelTask(taskId));
        notifyScheduler();
        return resp;
    }

    @PostMapping("/{taskId}/execute")
    public ResponseEntity<Map<String, Object>> executeTask(@PathVariable String taskId) {
        ResponseEntity<Map<String, Object>> resp = ok(taskService.executeTask(taskId));
        notifyScheduler();
        return resp;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> taskStats() {
        Map<String, Object> stats = taskService.stats();
        // 2026-08-15：scheduler_running 不再硬编码 false，由装配状态如实反映
        stats.put("scheduler_running", scheduler != null);
        return ok(stats);
    }

    @GetMapping("/actions")
    public ResponseEntity<Map<String, Object>> taskActions() {
        return ok(taskService.actions());
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }

    private void notifyScheduler() {
        if (scheduler != null) {
            scheduler.refresh();
        }
    }
}

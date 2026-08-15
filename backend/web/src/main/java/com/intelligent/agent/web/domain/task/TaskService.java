package com.intelligent.agent.web.domain.task;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务领域服务（Plan 2 / Task 3）。
 * <p>
 * create / list / patch / delete / cancel / stats / actions 与 Python
 * simple_scheduler 的 SimpleTask.to_dict() 形状一致；调度执行由
 * TaskSchedulerService 接入。
 * <p>
 * 2026-08-15 起支持磁盘持久化：构造传入 dataDir 时启动加载
 * {@code data/tasks.json}，每次变更原子写回（对齐 Python tasks.json 重启恢复）。
 */
@Slf4j
public class TaskService {

    public static final List<String> SUPPORTED_ACTIONS = List.of("log", "llm_generate");

    private final Map<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();
    private final Path dataDir;

    /** 纯内存模式（测试/无数据目录场景）。 */
    public TaskService() {
        this(null);
    }

    public TaskService(Path dataDir) {
        this.dataDir = dataDir;
        load();
    }

    public Map<String, Object> listTasks(String status, int limit) {
        List<Map<String, Object>> result = tasks.values().stream()
                .filter(task -> status == null || status.isBlank()
                        || status.equals(task.get("status")))
                .sorted(Comparator.comparing(
                        task -> String.valueOf(task.getOrDefault("created_at", ""))))
                .limit(Math.max(1, limit))
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tasks", result);
        response.put("count", result.size());
        return response;
    }

    public Map<String, Object> createTask(Map<String, Object> body) {
        Map<String, Object> task = new LinkedHashMap<>();
        String id = str(body.get("id"));
        task.put("id", id == null || id.isBlank()
                ? "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) : id);
        task.put("name", str(body.get("name")));
        task.put("description", str(body.getOrDefault("description", "")));
        task.put("action", str(body.getOrDefault("action", "log")));
        task.put("args", body.getOrDefault("args", Map.of()));
        task.put("schedule_type", str(body.getOrDefault("schedule_type", "immediate")));
        task.put("interval_seconds", body.get("interval_seconds"));
        task.put("delay_seconds", body.get("delay_seconds"));
        task.put("run_at", body.get("run_at"));
        task.put("cron_expression", body.get("cron_expr"));
        task.put("max_runs", body.get("max_runs"));
        task.put("retry_count", 0);
        task.put("max_retries", 3);
        task.put("status", "pending");
        String now = Instant.now().toString();
        task.put("created_at", now);
        task.put("started_at", null);
        task.put("completed_at", null);
        task.put("last_run", null);
        task.put("next_run", null);
        task.put("run_count", 0);
        task.put("last_result", null);
        task.put("last_error", null);
        task.put("tags", body.getOrDefault("tags", List.of()));
        task.put("metadata", Map.of());

        if (task.get("name") == null || String.valueOf(task.get("name")).isBlank()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "任务名称不能为空");
            return error;
        }
        if ("cron".equals(task.get("schedule_type"))
                && (task.get("cron_expression") == null
                || String.valueOf(task.get("cron_expression")).isBlank())) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "Cron 任务需要 cron_expr 参数");
            return error;
        }

        tasks.put((String) task.get("id"), task);
        log.info("任务已创建: id={}, name={}, action={}", task.get("id"), task.get("name"), task.get("action"));
        persist();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("task", task);
        return response;
    }

    public Map<String, Object> updateTask(String taskId, Map<String, Object> body) {
        Map<String, Object> task = tasks.get(taskId);
        if (task == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "任务不存在");
            return error;
        }
        Map<String, Object> merged = new LinkedHashMap<>(task);
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if ("message".equals(entry.getKey()) || "role".equals(entry.getKey())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = new LinkedHashMap<>(
                        merged.get("args") instanceof Map ? (Map<String, Object>) merged.get("args") : Map.of());
                args.put(entry.getKey(), entry.getValue());
                merged.put("args", args);
            } else if (!"id".equals(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        tasks.put(taskId, merged);
        persist();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("task", merged);
        return response;
    }

    public Map<String, Object> deleteTask(String taskId) {
        boolean success = tasks.remove(taskId) != null;
        persist();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", success);
        response.put("task_id", taskId);
        response.put("message", success ? "任务已删除" : "任务删除失败");
        return response;
    }

    public Map<String, Object> cancelTask(String taskId) {
        Map<String, Object> task = tasks.get(taskId);
        boolean success = task != null;
        if (success) {
            task.put("status", "cancelled");
            persist();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", success);
        response.put("task_id", taskId);
        response.put("message", success ? "任务已取消" : "任务取消失败");
        return response;
    }

    public Map<String, Object> executeTask(String taskId) {
        Map<String, Object> task = tasks.get(taskId);
        if (task == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "任务不存在");
            return error;
        }
        if ("running".equals(task.get("status"))) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "任务正在执行中，请等待当前轮次完成");
            return error;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "任务已触发，后台执行中");
        return response;
    }

    public Map<String, Object> stats() {
        Map<String, Object> byStatus = new LinkedHashMap<>();
        int totalRuns = 0;
        for (Map<String, Object> task : tasks.values()) {
            String status = str(task.get("status"));
            byStatus.merge(status, 1, (a, b) -> ((Number) a).intValue() + ((Number) b).intValue());
            Object runCount = task.get("run_count");
            totalRuns += runCount instanceof Number ? ((Number) runCount).intValue() : 0;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total_tasks", tasks.size());
        response.put("tasks_by_status", byStatus);
        response.put("total_runs", totalRuns);
        response.put("upcoming_tasks", tasks.values().stream()
                .filter(task -> "pending".equals(task.get("status"))).count());
        response.put("scheduler_running", false);
        return response;
    }

    public Map<String, Object> actions() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("actions", SUPPORTED_ACTIONS);
        return response;
    }

    /** 调度器访问入口：返回所有任务（共享引用，供调度器原地更新）。 */
    public List<Map<String, Object>> allTasks() {
        return new ArrayList<>(tasks.values());
    }

    /** 调度器写回任务状态。 */
    public void saveTask(Map<String, Object> task) {
        if (task != null && task.get("id") != null) {
            tasks.put((String) task.get("id"), task);
            persist();
        }
    }

    // ── 磁盘持久化（dataDir 非空时启用） ───────────────────────────────

    private Path tasksFile() {
        return dataDir.resolve("tasks.json");
    }

    private synchronized void persist() {
        if (dataDir == null) {
            return;
        }
        try {
            Files.createDirectories(dataDir);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tasks", new ArrayList<>(tasks.values()));
            Path file = tasksFile();
            Path tmp = file.resolveSibling("tasks.json.tmp");
            Files.writeString(tmp, new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(data),
                    StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("任务持久化失败: {}", e.getMessage());
        }
    }

    private void load() {
        if (dataDir == null || !Files.exists(tasksFile())) {
            return;
        }
        try {
            Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    Files.readString(tasksFile(), StandardCharsets.UTF_8),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Object list = data.get("tasks");
            if (list instanceof List) {
                for (Object item : (List<?>) list) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> task = (Map<String, Object>) item;
                        if (task.get("id") != null) {
                            tasks.put(String.valueOf(task.get("id")), task);
                        }
                    }
                }
            }
            log.info("任务已从 {} 恢复 {} 条", tasksFile(), tasks.size());
        } catch (Exception e) {
            log.warn("任务加载失败（以空表启动）: {}", e.getMessage());
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

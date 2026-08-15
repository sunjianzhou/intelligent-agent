package com.intelligent.agent.web.domain.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务持久化契约（2026-08-15 补齐，对齐 Python tasks.json 重启恢复）：
 * 创建后落盘、重建服务实例后恢复、删除后同步删除。
 */
class TaskServicePersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void createTaskPersistsAndReloads() {
        TaskService service = new TaskService(tempDir);
        Map<String, Object> created = service.createTask(Map.of(
                "name", "持久化任务",
                "action", "log",
                "schedule_type", "delay",
                "delay_seconds", 60,
                "args", Map.of("message", "hi")));

        assertThat(created.get("success")).isEqualTo(true);
        assertThat(Files.exists(tempDir.resolve("tasks.json"))).isTrue();

        TaskService reloaded = new TaskService(tempDir);
        assertThat(reloaded.listTasks(null, 100).get("count")).isEqualTo(1);
        Map<String, Object> task = reloaded.allTasks().get(0);
        assertThat(task.get("name")).isEqualTo("持久化任务");
        assertThat(task.get("action")).isEqualTo("log");
        assertThat(task.get("status")).isEqualTo("pending");
    }

    @Test
    void deleteTaskPersistsRemoval() {
        TaskService service = new TaskService(tempDir);
        Map<String, Object> created = service.createTask(Map.of("name", "待删"));
        String id = String.valueOf(((Map<?, ?>) created.get("task")).get("id"));

        service.deleteTask(id);

        TaskService reloaded = new TaskService(tempDir);
        assertThat(reloaded.listTasks(null, 100).get("count")).isEqualTo(0);
    }

    @Test
    void saveTaskStatusPersists() {
        TaskService service = new TaskService(tempDir);
        Map<String, Object> created = service.createTask(Map.of("name", "状态任务"));
        Map<?, ?> task = (Map<?, ?>) created.get("task");
        String id = String.valueOf(task.get("id"));

        Map<String, Object> running = new java.util.LinkedHashMap<>();
        running.put("id", id);
        running.put("status", "completed");
        running.put("run_count", 1);
        service.saveTask(running);

        TaskService reloaded = new TaskService(tempDir);
        Map<String, Object> restored = reloaded.allTasks().get(0);
        assertThat(restored.get("status")).isEqualTo("completed");
        assertThat(restored.get("run_count")).isEqualTo(1);
    }

    @Test
    void noArgConstructorStaysInMemory() {
        TaskService service = new TaskService();
        service.createTask(Map.of("name", "内存任务"));
        assertThat(service.listTasks(null, 10).get("count")).isEqualTo(1);
        // 无 dataDir：不应抛异常（persist 静默跳过）
        service.saveTask(Map.of("id", "task_x", "status", "running"));
        assertThat(service.allTasks().size()).isGreaterThanOrEqualTo(1);
    }
}

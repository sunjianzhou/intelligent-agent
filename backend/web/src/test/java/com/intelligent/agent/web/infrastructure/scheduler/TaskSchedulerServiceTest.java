package com.intelligent.agent.web.infrastructure.scheduler;

import com.intelligent.agent.web.domain.task.TaskService;
import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事件驱动调度：refresh() 按最近到期时刻安排唤醒，取代每秒全量盲扫。
 */
class TaskSchedulerServiceTest {

    @TempDir
    Path dataDir;

    @Test
    void nextRunInstantComputedForDelayTask() {
        TaskService taskService = new TaskService();
        TaskSchedulerService scheduler = new TaskSchedulerService(taskService, dataDir);
        Instant created = Instant.now().minusSeconds(10);
        Map<String, Object> createdResult = taskService.createTask(Map.of(
                "name", "t", "action", "log", "schedule_type", "delay",
                "delay_seconds", 60));
        Object taskObj = createdResult.get("task");
        Map<String, Object> task = taskObj instanceof Map
                ? (Map<String, Object>) taskObj : Map.of();
        Instant base = Instant.parse(String.valueOf(task.get("created_at")));

        Instant next = scheduler.nextRunInstant(task);

        assertThat(next).isEqualTo(base.plusSeconds(60));
    }

    @Test
    void refreshSchedulesWakeupAtEarliestDueTask() {
        TaskService taskService = new TaskService();
        TaskScheduler schedulerMock = mock(TaskScheduler.class);
        when(schedulerMock.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));
        TaskSchedulerService scheduler = new TaskSchedulerService(
                taskService, dataDir, schedulerMock, null);

        taskService.createTask(Map.of(
                "name", "far", "action", "log", "schedule_type", "delay",
                "delay_seconds", 600, "created_at", Instant.now().toString()));
        taskService.createTask(Map.of(
                "name", "near", "action", "log", "schedule_type", "delay",
                "delay_seconds", 5, "created_at", Instant.now().toString()));

        scheduler.refresh();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(schedulerMock).schedule(any(Runnable.class), captor.capture());
        assertThat(captor.getValue()).isBetween(
                Instant.now().plusSeconds(4), Instant.now().plusSeconds(6));
    }

    @Test
    void immediateTaskSchedulesNow() {
        TaskService taskService = new TaskService();
        TaskScheduler schedulerMock = mock(TaskScheduler.class);
        when(schedulerMock.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));
        TaskSchedulerService scheduler = new TaskSchedulerService(
                taskService, dataDir, schedulerMock, null);
        taskService.createTask(Map.of(
                "name", "now", "action", "log", "schedule_type", "immediate"));

        scheduler.refresh();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(schedulerMock).schedule(any(Runnable.class), captor.capture());
        assertThat(captor.getValue()).isBeforeOrEqualTo(Instant.now().plus(1, ChronoUnit.SECONDS));
    }

    @Test
    void arbitraryToolNameActionExecutesRegisteredToolAndNotifies() {
        TaskService taskService = new TaskService();
        ToolExecutor toolExecutor = new ToolExecutor(List.of(new StubTool("stub_echo")));
        ObjectProvider<ToolExecutor> provider = new ObjectProvider<>() {
            @Override
            public ToolExecutor getObject() {
                return toolExecutor;
            }

            @Override
            public ToolExecutor getObject(Object... args) {
                return toolExecutor;
            }

            @Override
            public ToolExecutor getIfAvailable() {
                return toolExecutor;
            }

            @Override
            public ToolExecutor getIfUnique() {
                return toolExecutor;
            }
        };
        TaskSchedulerService scheduler = new TaskSchedulerService(
                taskService, dataDir, null, null, provider);
        Map<String, Object> created = taskService.createTask(Map.of(
                "name", "tool任务", "action", "stub_echo",
                "args", Map.of("text", "hello")));
        Map<?, ?> task = (Map<?, ?>) created.get("task");
        @SuppressWarnings("unchecked")
        Map<String, Object> taskMap = (Map<String, Object>) task;

        scheduler.run(taskMap);

        assertThat(taskMap.get("status")).isEqualTo("completed");
        assertThat(String.valueOf(taskMap.get("last_result"))).isEqualTo("echo:hello");
        assertThat(scheduler.pollNotifications()).hasSize(1);
    }

    @Test
    void unregisteredToolActionMarksFailed() {
        TaskService taskService = new TaskService();
        TaskSchedulerService scheduler = new TaskSchedulerService(taskService, dataDir);
        Map<String, Object> created = taskService.createTask(Map.of(
                "name", "无此工具", "action", "no_such_tool"));
        @SuppressWarnings("unchecked")
        Map<String, Object> taskMap = (Map<String, Object>) created.get("task");

        scheduler.run(taskMap);

        assertThat(taskMap.get("status")).isEqualTo("failed");
        assertThat(String.valueOf(taskMap.get("last_error"))).contains("action 未注册");
    }

    /** 测试桩工具：stub_echo(text) → "echo:" + text。 */
    static class StubTool implements AgentTool {
        private final String name;

        StubTool(String name) {
            this.name = name;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name, "echo stub", false, null, null,
                    Map.of("type", "object", "properties", Map.of("text",
                            Map.of("type", "string"))));
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            return "echo:" + arguments.getOrDefault("text", "");
        }
    }
}

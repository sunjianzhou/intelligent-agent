package com.intelligent.agent.web.infrastructure.scheduler;

import com.intelligent.agent.web.domain.task.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
}

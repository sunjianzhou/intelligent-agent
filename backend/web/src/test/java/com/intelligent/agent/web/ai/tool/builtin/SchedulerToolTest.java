package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.domain.task.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提醒 / 定时任务工具契约（2026-08-15 补齐）：
 * create_reminder / create_periodic_reminder / create_onetime_ai_task /
 * create_periodic_ai_task / list_tasks 与 Python FunctionTool 对齐。
 */
class SchedulerToolTest {

    @TempDir
    Path tempDir;

    @Test
    void createReminderCreatesDelayLogTask() {
        TaskService taskService = new TaskService(tempDir);
        SchedulerTool tool = new SchedulerTool(
                SchedulerTool.CREATE_REMINDER, taskService, null);

        Object result = tool.execute(Map.of("message", "喝水", "remind_in_seconds", 60));

        assertThat(String.valueOf(result)).contains("已创建任务");
        Map<String, Object> task = taskService.allTasks().get(0);
        assertThat(task.get("action")).isEqualTo("log");
        assertThat(task.get("schedule_type")).isEqualTo("delay");
        assertThat(task.get("delay_seconds")).isEqualTo(60);
        assertThat(String.valueOf(task.get("name"))).contains("喝水");
    }

    @Test
    void createPeriodicReminderCreatesIntervalLogTask() {
        TaskService taskService = new TaskService(tempDir);
        SchedulerTool tool = new SchedulerTool(
                SchedulerTool.CREATE_PERIODIC_REMINDER, taskService, null);

        Object result = tool.execute(Map.of("message", "每小时提醒", "interval_seconds", 3600));

        assertThat(String.valueOf(result)).contains("已创建任务");
        Map<String, Object> task = taskService.allTasks().get(0);
        assertThat(task.get("schedule_type")).isEqualTo("interval");
        assertThat(task.get("interval_seconds")).isEqualTo(3600);
    }

    @Test
    void createOnetimeAiTaskUsesLlmGenerateAction() {
        TaskService taskService = new TaskService(tempDir);
        SchedulerTool tool = new SchedulerTool(
                SchedulerTool.CREATE_ONETIME_AI_TASK, taskService, null);

        Object result = tool.execute(Map.of("prompt", "生成日报", "remind_in_seconds", 30));

        assertThat(String.valueOf(result)).contains("已创建任务");
        Map<String, Object> task = taskService.allTasks().get(0);
        assertThat(task.get("action")).isEqualTo("llm_generate");
        assertThat(task.get("schedule_type")).isEqualTo("delay");
    }

    @Test
    void listTasksReturnsFormattedList() {
        TaskService taskService = new TaskService(tempDir);
        taskService.createTask(Map.of("name", "任务A", "action", "log", "schedule_type", "delay"));
        SchedulerTool tool = new SchedulerTool(SchedulerTool.LIST_TASKS, taskService, null);

        Object result = tool.execute(Map.of("limit", 50));

        assertThat(String.valueOf(result)).contains("任务A").contains("action=log");
    }

    @Test
    void createReminderRejectsBlankMessage() {
        TaskService taskService = new TaskService(tempDir);
        SchedulerTool tool = new SchedulerTool(
                SchedulerTool.CREATE_REMINDER, taskService, null);

        Object result = tool.execute(Map.of("message", "  "));

        assertThat(String.valueOf(result)).contains("失败");
        assertThat(taskService.allTasks()).isEmpty();
    }
}

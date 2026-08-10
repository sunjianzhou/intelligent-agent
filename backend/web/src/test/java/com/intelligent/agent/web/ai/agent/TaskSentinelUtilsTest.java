package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.llm.ModelEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务标记工具测试（TODO-110 Task 4.3）。
 */
class TaskSentinelUtilsTest {

    @Test
    void extractsTaskDoneAndBlockedEvents() {
        String text = "完成了 [TASK_DONE:task-001] 和 [TASK_BLOCKED:task-002]";
        List<ModelEvent> events = TaskSentinelUtils.events(text, "proj-1");

        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo("task_update");
        assertThat((Map<String, Object>) events.get(0).data())
                .containsEntry("task_id", "task-001")
                .containsEntry("status", "done")
                .containsEntry("project_id", "proj-1");
        assertThat(events.get(1).type()).isEqualTo("task_blocked");
        assertThat((Map<String, Object>) events.get(1).data()).containsEntry("task_id", "task-002");
    }

    @Test
    void supportsMultipleAndUnnamedSentinels() {
        String text = "[TASK_DONE] [TASK_DONE:a] [TASK_DONE:b]";
        List<ModelEvent> events = TaskSentinelUtils.events(text, "p");

        assertThat(events).hasSize(3);
        assertThat((Map<String, Object>) events.get(0).data()).containsEntry("task_id", null);
    }

    @Test
    void noEventsWithoutProjectId() {
        assertThat(TaskSentinelUtils.events("[TASK_DONE:x]", null)).isEmpty();
        assertThat(TaskSentinelUtils.events("[TASK_DONE:x]", "")).isEmpty();
    }

    @Test
    void stripsSentinelsFromDisplayText() {
        String stripped = TaskSentinelUtils.strip("好的 [TASK_DONE:task-001] 已完成\n[TASK_BLOCKED:task-2]");
        assertThat(stripped).isEqualTo("好的  已完成");
    }
}

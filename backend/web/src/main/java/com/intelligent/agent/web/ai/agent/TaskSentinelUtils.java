package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.llm.ModelEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [TASK_DONE] / [TASK_BLOCKED] 任务标记工具（对齐 Python _strip_task_sentinels）。
 *
 * <p>扫描完整回复中的所有标记（支持多条与不带 id 的形式），生成
 * task_update / task_blocked 事件，并从显示文本中剥除标记。</p>
 */
public final class TaskSentinelUtils {

    private static final Pattern DONE_RE = Pattern.compile("\\[TASK_DONE(?::([^\\]]*))?\\]");
    private static final Pattern BLOCKED_RE = Pattern.compile("\\[TASK_BLOCKED(?::([^\\]]*))?\\]");
    private static final Pattern ALL_RE = Pattern.compile(
            "\\[TASK_DONE(?::([^\\]]*))?\\]|\\[TASK_BLOCKED(?::([^\\]]*))?\\]");

    private TaskSentinelUtils() {
    }

    /** 扫描文本中的任务标记，生成事件列表（无 projectId 或无可检测标记时为空列表）。 */
    public static List<ModelEvent> events(String fullResponse, String projectId) {
        List<ModelEvent> events = new ArrayList<>();
        if (fullResponse == null || fullResponse.isBlank()
                || projectId == null || projectId.isBlank()) {
            return events;
        }
        String now = Instant.now().toString();
        Matcher done = DONE_RE.matcher(fullResponse);
        while (done.find()) {
            events.add(ModelEvent.taskUpdate(taskData(projectId, group(done.group(1)), "done", now)));
        }
        Matcher blocked = BLOCKED_RE.matcher(fullResponse);
        while (blocked.find()) {
            events.add(ModelEvent.taskBlocked(taskData(projectId, group(blocked.group(1)), "blocked", now)));
        }
        return events;
    }

    /** 从显示文本中剥除所有任务标记。 */
    public static String strip(String fullResponse) {
        if (fullResponse == null || fullResponse.isBlank()) {
            return fullResponse == null ? "" : fullResponse;
        }
        return ALL_RE.matcher(fullResponse).replaceAll("").strip();
    }

    private static String group(String value) {
        return value == null ? "" : value.strip();
    }

    private static Map<String, Object> taskData(String projectId, String taskId,
                                                String status, String ts) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("project_id", projectId);
        data.put("task_id", taskId.isEmpty() ? null : taskId);
        data.put("status", status);
        data.put("ts", ts);
        return data;
    }
}

package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.ai.tool.ToolExecutionContext;
import com.intelligent.agent.web.domain.task.TaskService;
import com.intelligent.agent.web.infrastructure.scheduler.TaskSchedulerService;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提醒 / 定时任务 AgentTool（2026-08-15 补齐，对齐 Python FunctionTool 的
 * create_reminder / create_periodic_reminder / create_onetime_ai_task /
 * create_periodic_ai_task / list_tasks）。
 * <p>
 * 任务创建走 {@link TaskService}（落盘 tasks.json），创建后触发
 * {@link TaskSchedulerService#refresh()} 让事件驱动唤醒立即感知。
 */
public class SchedulerTool implements AgentTool {

    public static final String CREATE_REMINDER = "create_reminder";
    public static final String CREATE_PERIODIC_REMINDER = "create_periodic_reminder";
    public static final String CREATE_ONETIME_AI_TASK = "create_onetime_ai_task";
    public static final String CREATE_PERIODIC_AI_TASK = "create_periodic_ai_task";
    public static final String LIST_TASKS = "list_tasks";

    private final String name;
    private final TaskService taskService;
    private final TaskSchedulerService scheduler;

    public SchedulerTool(String name, TaskService taskService, TaskSchedulerService scheduler) {
        this.name = name;
        this.taskService = taskService;
        this.scheduler = scheduler;
    }

    @Override
    public ToolDefinition definition() {
        switch (name) {
            case CREATE_REMINDER:
                return new ToolDefinition(name,
                        "创建一次性提醒：N 秒后向用户推送一条提醒消息。"
                                + "用户说\"X秒/分钟后提醒我\"时使用。参数: message(提醒内容,必填),"
                                + " remind_in_seconds(秒,默认60)。",
                        false, null, Duration.ofSeconds(30),
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "message", Map.of("type", "string", "description", "提醒内容"),
                                        "remind_in_seconds", Map.of("type", "integer",
                                                "description", "多少秒后提醒，默认 60")),
                                "required", List.of("message")));
            case CREATE_PERIODIC_REMINDER:
                return new ToolDefinition(name,
                        "创建周期性提醒：每隔 N 秒推送一次固定提醒消息。"
                                + "用户说\"每隔X分钟提醒我\"时使用。参数: message(提醒内容,必填),"
                                + " interval_seconds(秒,默认3600)。",
                        false, null, Duration.ofSeconds(30),
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "message", Map.of("type", "string", "description", "提醒内容"),
                                        "interval_seconds", Map.of("type", "integer",
                                                "description", "间隔秒数，默认 3600")),
                                "required", List.of("message")));
            case CREATE_ONETIME_AI_TASK:
                return new ToolDefinition(name,
                        "创建一次性 AI 生成任务：N 秒后调用 LLM 生成内容并推送。"
                                + "用户说\"X分钟后生成一份报告/总结\"时使用。参数: prompt(生成指令,必填),"
                                + " remind_in_seconds(秒,默认60)。",
                        false, null, Duration.ofSeconds(30),
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "prompt", Map.of("type", "string", "description", "生成指令"),
                                        "remind_in_seconds", Map.of("type", "integer",
                                                "description", "多少秒后执行，默认 60")),
                                "required", List.of("prompt")));
            case CREATE_PERIODIC_AI_TASK:
                return new ToolDefinition(name,
                        "创建周期性 AI 生成任务：每隔 N 秒调用 LLM 生成新内容并推送。"
                                + "参数: prompt(生成指令,必填), interval_seconds(秒,默认3600)。",
                        false, null, Duration.ofSeconds(30),
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "prompt", Map.of("type", "string", "description", "生成指令"),
                                        "interval_seconds", Map.of("type", "integer",
                                                "description", "间隔秒数，默认 3600")),
                                "required", List.of("prompt")));
            default: // list_tasks
                return new ToolDefinition(name,
                        "列出当前所有定时任务/提醒。参数: limit(最多返回条数,默认50)。",
                        true, null, Duration.ofSeconds(10),
                        Map.of(
                                "type", "object",
                                "properties", Map.of("limit", Map.of("type", "integer",
                                        "description", "最多返回条数，默认 50")),
                                "required", List.of()));
        }
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        return execute(arguments, null);
    }

    /** 2026-08-15：任务归属执行上下文中的用户，提醒通知按用户分发。 */
    @Override
    public Object execute(Map<String, Object> arguments, ToolExecutionContext context) {
        String userId = context == null || context.userId() == null || context.userId().isBlank()
                ? null : context.userId();
        switch (name) {
            case CREATE_REMINDER: {
                String message = str(arguments.get("message"));
                int seconds = intOr(arguments.get("remind_in_seconds"), 60);
                return create(userId, message, "log", "delay", seconds, 0, "⏰ " + message);
            }
            case CREATE_PERIODIC_REMINDER: {
                String message = str(arguments.get("message"));
                int interval = intOr(arguments.get("interval_seconds"), 3600);
                return create(userId, message, "log", "interval", 0, interval, "⏰ " + message);
            }
            case CREATE_ONETIME_AI_TASK: {
                String prompt = str(arguments.get("prompt"));
                int seconds = intOr(arguments.get("remind_in_seconds"), 60);
                return create(userId, prompt, "llm_generate", "delay", seconds, 0, prompt);
            }
            case CREATE_PERIODIC_AI_TASK: {
                String prompt = str(arguments.get("prompt"));
                int interval = intOr(arguments.get("interval_seconds"), 3600);
                return create(userId, prompt, "llm_generate", "interval", 0, interval, prompt);
            }
            default: { // list_tasks
                int limit = intOr(arguments.get("limit"), 50);
                Map<String, Object> list = taskService.listTasks(null, limit);
                List<?> tasks = list.get("tasks") instanceof List
                        ? (List<?>) list.get("tasks") : List.of();
                StringBuilder sb = new StringBuilder();
                for (Object item : tasks) {
                    if (item instanceof Map) {
                        Map<?, ?> t = (Map<?, ?>) item;
                        sb.append("- ").append(t.get("id")).append(" [")
                                .append(t.get("status") == null ? "?" : t.get("status")).append("] ")
                                .append(t.get("name") == null ? "" : t.get("name")).append(" (action=")
                                .append(t.get("action") == null ? "" : t.get("action")).append(")\n");
                    }
                }
                return sb.isEmpty() ? "当前没有定时任务/提醒" : sb.toString().stripTrailing();
            }
        }
    }

    private Object create(String userId, String content, String action, String scheduleType,
                          int delaySeconds, int intervalSeconds, String taskName) {
        if (content == null || content.isBlank()) {
            return "创建失败: 内容不能为空";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", taskName.length() > 50 ? taskName.substring(0, 50) : taskName);
        body.put("action", action);
        body.put("schedule_type", scheduleType);
        if (userId != null) {
            body.put("user_id", userId);
        }
        body.put("args", Map.of("message", content));
        if ("delay".equals(scheduleType)) {
            body.put("delay_seconds", Math.max(1, delaySeconds));
        } else {
            body.put("interval_seconds", Math.max(1, intervalSeconds));
        }
        Map<String, Object> created = taskService.createTask(body);
        if (!Boolean.TRUE.equals(created.get("success"))) {
            return "创建失败: " + created.getOrDefault("message", "未知错误");
        }
        if (scheduler != null) {
            scheduler.refresh();
        }
        Map<?, ?> task = created.get("task") instanceof Map
                ? (Map<?, ?>) created.get("task") : Map.of();
        return "已创建任务: id=" + task.get("id") + ", name=" + task.get("name")
                + ", schedule_type=" + task.get("schedule_type");
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intOr(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }
}

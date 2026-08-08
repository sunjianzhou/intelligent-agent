package com.intelligent.agent.web.infrastructure.scheduler;

import com.intelligent.agent.web.domain.task.TaskService;
import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;

/**
 * 任务调度服务（Plan 2 / Task 5）：
 * 每秒 tick，按 immediate / delay / interval / datetime / cron 计算到期任务，
 * 执行动作并更新任务状态。当前动作：log（写入 actions.log）；
 * 其他动作标记 failed（等待 Plan 3 工具注册接入）。
 */
@Slf4j
public class TaskSchedulerService {

    private final TaskService taskService;
    private final Path actionLog;
    private final TaskScheduler taskScheduler;
    private final Queue<Map<String, Object>> notifications = new ConcurrentLinkedQueue<>();
    private final LlmProviderRouter llmRouter;
    private ScheduledFuture<?> scheduledFuture;

    public TaskSchedulerService(TaskService taskService, Path dataDir) {
        this(taskService, dataDir, null, null);
    }

    public TaskSchedulerService(TaskService taskService, Path dataDir, TaskScheduler taskScheduler) {
        this(taskService, dataDir, taskScheduler, null);
    }

    public TaskSchedulerService(TaskService taskService, Path dataDir, TaskScheduler taskScheduler,
                                LlmProviderRouter llmRouter) {
        this.taskService = taskService;
        this.actionLog = dataDir.resolve("actions.log");
        this.taskScheduler = taskScheduler;
        this.llmRouter = llmRouter;
    }

    @PostConstruct
    public void start() {
        if (taskScheduler != null) {
            scheduledFuture = taskScheduler.scheduleAtFixedRate(
                    this::tick, Duration.ofSeconds(1));
            log.info("任务调度器已启动（每秒 tick）");
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
    }

    /** 单次调度 tick：扫描所有 pending 任务，执行到期的。 */
    public void tick() {
        for (Map<String, Object> task : taskService.allTasks()) {
            if (!"pending".equals(task.get("status"))) {
                continue;
            }
            if (shouldRun(task)) {
                run(task);
            }
        }
    }

    boolean shouldRun(Map<String, Object> task) {
        String scheduleType = str(task.get("schedule_type"));
        int runCount = num(task.get("run_count"));
        Object maxRuns = task.get("max_runs");
        if (maxRuns instanceof Number && runCount >= ((Number) maxRuns).intValue()) {
            return false;
        }
        Instant now = Instant.now();
        Instant lastRun = parseInstant(task.get("last_run"));
        switch (scheduleType == null ? "immediate" : scheduleType) {
            case "immediate":
                return true;
            case "delay": {
                int delay = num(task.get("delay_seconds"));
                Instant base = lastRun != null ? lastRun : parseInstant(task.get("created_at"));
                return base != null && !now.isBefore(base.plusSeconds(delay));
            }
            case "interval": {
                int interval = num(task.get("interval_seconds"));
                if (lastRun == null) {
                    return true;
                }
                return !now.isBefore(lastRun.plusSeconds(interval));
            }
            case "datetime": {
                Instant runAt = parseInstant(task.get("run_at"));
                return runAt != null && !now.isBefore(runAt);
            }
            case "cron": {
                // Python croniter 为 5 字段；Spring CronExpression 需 6 字段，补秒位
                String expr = str(task.get("cron_expression"));
                try {
                    CronExpression cron = CronExpression.parse("0 " + expr);
                    Instant base = lastRun != null ? lastRun : parseInstant(task.get("created_at"));
                    return base != null && !now.isBefore(cron.next(base));
                } catch (Exception e) {
                    return false;
                }
            }
            default:
                return false;
        }
    }

    void run(Map<String, Object> task) {
        String now = Instant.now().toString();
        task.put("status", "running");
        task.put("last_run", now);
        task.put("run_count", num(task.get("run_count")) + 1);
        String action = str(task.get("action"));
        try {
            if ("log".equals(action)) {
                String message = messageOf(task.get("args"));
                appendLog(now, message);
                notify(now, task, message);
                task.put("last_result", "logged");
                task.put("status", "completed");
                task.put("completed_at", now);
            } else if ("llm_generate".equals(action)) {
                if (llmRouter == null) {
                    task.put("last_error", "llm_generate 未配置 LLM 路由");
                    task.put("status", "failed");
                } else {
                    String prompt = messageOf(task.get("args"));
                    if (prompt.isBlank()) {
                        prompt = String.valueOf(task.getOrDefault("name", "生成一段文字"));
                    }
                    String text = llmRouter.forUser("default", null)
                            .complete(new ChatTurn("default", null,
                                    List.of(ChatMessage.user(prompt)), Map.of()))
                            .block(Duration.ofSeconds(120));
                    notify(now, task, text);
                    task.put("last_result", text);
                    task.put("status", "completed");
                    task.put("completed_at", now);
                }
            } else {
                task.put("last_error", "action 未注册: " + action);
                task.put("status", "failed");
            }
        } catch (Exception e) {
            log.error("任务执行失败 taskId={}: {}", task.get("id"), e.getMessage());
            task.put("last_error", e.getMessage());
            task.put("status", "failed");
        }
        taskService.saveTask(task);
    }

    private void notify(String now, Map<String, Object> task, String message) {
        notifications.add(Map.of(
                "message", message,
                "timestamp", now,
                "task_id", String.valueOf(task.getOrDefault("id", ""))));
    }

    private void appendLog(String timestamp, String message) {
        try {
            Files.createDirectories(actionLog.getParent());
            Files.writeString(actionLog,
                    timestamp + " " + (message == null ? "" : message) + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("写入 actions.log 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String messageOf(Object args) {
        if (!(args instanceof Map)) {
            return String.valueOf(args);
        }
        Object message = ((Map<String, Object>) args).get("message");
        return message == null ? "" : String.valueOf(message);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int num(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    /** 取出并清空待推送通知（前端每 30s 轮询一次）。 */
    public List<Map<String, Object>> pollNotifications() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        Map<String, Object> next;
        while ((next = notifications.poll()) != null) {
            out.add(next);
        }
        return out;
    }
}

package com.intelligent.agent.web.infrastructure.scheduler;

import com.intelligent.agent.web.domain.task.TaskService;
import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.ai.tool.ToolCall;
import com.intelligent.agent.web.ai.tool.ToolExecutionContext;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.ai.tool.ToolResult;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 任务调度服务（Plan 2 / Task 5）：
 * 每秒 tick，按 immediate / delay / interval / datetime / cron 计算到期任务，
 * 执行动作并更新任务状态。动作：log（写入 actions.log）、llm_generate（LLM 生成）、
 * 任意已注册工具名（2026-08-15 补齐，注入 ToolExecutor）。
 */
@Slf4j
public class TaskSchedulerService {

    private final TaskService taskService;
    private final Path actionLog;
    private final TaskScheduler taskScheduler;
    private final Queue<Map<String, Object>> notifications = new ConcurrentLinkedQueue<>();
    private final LlmProviderRouter llmRouter;
    private final ObjectProvider<ToolExecutor> toolExecutorProvider;
    private final ExecutorService taskExecutor;
    private final AtomicBoolean ticking = new AtomicBoolean(false);
    private ScheduledFuture<?> scheduledFuture;
    private ScheduledFuture<?> wakeupFuture;

    public TaskSchedulerService(TaskService taskService, Path dataDir) {
        this(taskService, dataDir, null, null);
    }

    public TaskSchedulerService(TaskService taskService, Path dataDir, TaskScheduler taskScheduler) {
        this(taskService, dataDir, taskScheduler, null);
    }

    public TaskSchedulerService(TaskService taskService, Path dataDir, TaskScheduler taskScheduler,
                                LlmProviderRouter llmRouter) {
        this(taskService, dataDir, taskScheduler, llmRouter, (ObjectProvider<ToolExecutor>) null);
    }

    public TaskSchedulerService(TaskService taskService, Path dataDir, TaskScheduler taskScheduler,
                                LlmProviderRouter llmRouter,
                                ObjectProvider<ToolExecutor> toolExecutorProvider) {
        this.taskService = taskService;
        this.actionLog = dataDir.resolve("actions.log");
        this.taskScheduler = taskScheduler;
        this.llmRouter = llmRouter;
        this.toolExecutorProvider = toolExecutorProvider;
        this.taskExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "agent-task-executor");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void start() {
        if (taskScheduler != null) {
            // 事件驱动：按最近到期时刻唤醒；60s 兜底扫描自愈漂移
            scheduledFuture = taskScheduler.scheduleAtFixedRate(
                    this::refresh, Duration.ofSeconds(60));
            refresh();
            log.info("任务调度器已启动（事件驱动，60s 兜底扫描）");
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        if (wakeupFuture != null) {
            wakeupFuture.cancel(false);
        }
        taskExecutor.shutdownNow();
    }

    /**
     * 异步 tick 入口（生产调度循环使用）：单飞行锁保证同一时刻至多一个 tick 在执行，
     * 且 tick 在专用线程池上运行——llm_generate 等长任务不再阻塞共享的 Spring 调度线程
     * （该线程同时服务 @Scheduled 通知推送）。tick 本身保持同步，供测试直调。
     */
    void tickAsync() {
        if (!ticking.compareAndSet(false, true)) {
            return;
        }
        try {
            taskExecutor.submit(() -> {
                try {
                    tick();
                } finally {
                    ticking.set(false);
                    refresh();
                }
            });
        } catch (RejectedExecutionException e) {
            ticking.set(false);
            log.warn("任务执行线程池已满，跳过本次 tick");
        }
    }

    /**
     * 事件驱动核心：取消旧唤醒，按所有 pending 任务里最近的到期时刻安排一次性唤醒。
     * 任务增删改（控制器调用）与每次执行完成后都会触发，取代每秒全量盲扫。
     */
    public synchronized void refresh() {
        if (taskScheduler == null) {
            return;
        }
        if (wakeupFuture != null) {
            wakeupFuture.cancel(false);
            wakeupFuture = null;
        }
        Instant earliest = null;
        for (Map<String, Object> task : taskService.allTasks()) {
            Instant next = nextRunInstant(task);
            if (next != null && (earliest == null || next.isBefore(earliest))) {
                earliest = next;
            }
        }
        if (earliest == null) {
            return;
        }
        Instant target = earliest.isBefore(Instant.now()) ? Instant.now() : earliest;
        wakeupFuture = taskScheduler.schedule(this::tickAsync, target);
    }

    /** 计算某 pending 任务的下一次到期时刻；不可运行返回 null。 */
    Instant nextRunInstant(Map<String, Object> task) {
        if (!"pending".equals(task.get("status"))) {
            return null;
        }
        Object maxRuns = task.get("max_runs");
        if (maxRuns instanceof Number
                && num(task.get("run_count")) >= ((Number) maxRuns).intValue()) {
            return null;
        }
        Instant now = Instant.now();
        Instant lastRun = parseInstant(task.get("last_run"));
        switch (str(task.get("schedule_type")) == null ? "immediate" : str(task.get("schedule_type"))) {
            case "delay": {
                Instant base = lastRun != null ? lastRun : parseInstant(task.get("created_at"));
                return base == null ? now : base.plusSeconds(num(task.get("delay_seconds")));
            }
            case "interval": {
                return lastRun == null ? now
                        : lastRun.plusSeconds(num(task.get("interval_seconds")));
            }
            case "datetime": {
                Instant runAt = parseInstant(task.get("run_at"));
                return runAt == null ? null : runAt;
            }
            case "cron": {
                Instant base = lastRun != null ? lastRun : parseInstant(task.get("created_at"));
                if (base == null) {
                    return null;
                }
                try {
                    CronExpression cron = CronExpression.parse("0 " + str(task.get("cron_expression")));
                    return cron.next(base);
                } catch (Exception e) {
                    return null;
                }
            }
            default: // immediate
                return now;
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
                executeToolAction(task, action);
            }
        } catch (Exception e) {
            log.error("任务执行失败 taskId={}: {}", task.get("id"), e.getMessage());
            task.put("last_error", e.getMessage());
            task.put("status", "failed");
        }
        taskService.saveTask(task);
    }

    /** 任意已注册工具名 action：以任务 args 作为工具参数执行，结果进入通知队列。 */
    private void executeToolAction(Map<String, Object> task, String action) {
        ToolExecutor toolExecutor = toolExecutorProvider == null ? null
                : toolExecutorProvider.getIfAvailable();
        if (toolExecutor == null || toolExecutor.definitions().stream()
                .noneMatch(d -> d.name().equals(action))) {
            task.put("last_error", "action 未注册: " + action);
            task.put("status", "failed");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> args = task.get("args") instanceof Map
                ? (Map<String, Object>) task.get("args") : Map.of();
        String userId = String.valueOf(task.getOrDefault("user_id", "scheduler"));
        ToolResult result = toolExecutor.execute(
                ToolCall.of(action, args),
                ToolExecutionContext.of(userId, "user"));
        String now = Instant.now().toString();
        if (ToolResult.SUCCESS.equals(result.status())) {
            Object data = result.data() != null ? result.data() : "ok";
            String text = String.valueOf(data);
            notify(now, task, text);
            task.put("last_result", text);
            task.put("status", "completed");
            task.put("completed_at", now);
        } else {
            task.put("last_error", "工具执行失败: "
                    + (result.error() != null ? result.error() : result.status()));
            task.put("status", "failed");
        }
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

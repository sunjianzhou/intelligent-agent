package com.intelligent.agent.web.domain.analytics;

import com.intelligent.agent.web.infrastructure.observability.AgentRunTrace;
import com.intelligent.agent.web.infrastructure.observability.TraceSpan;
import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计分析领域服务（Plan 2 / Task 4）：
 * 反馈 / Skill 日志 / 工具调用记录，JSON 文件持久化，形状与 Python
 * feedback_store / skill_log_store / tool_call_store 一致。
 */
@Slf4j
public class AnalyticsService {

    private final JsonFileStore store;
    /** R-10：成本配置（null = 不核算成本/不限额）。 */
    private final CostConfig costConfig;

    public AnalyticsService(Path dataDir) {
        this(dataDir, null);
    }

    public AnalyticsService(Path dataDir, CostConfig costConfig) {
        this.store = new JsonFileStore(dataDir);
        this.costConfig = costConfig;
    }

    /** R-10：模型单价配置（每百万 token，单位 CNY）。价格表按模型名精确或包含匹配。 */
    public record CostConfig(boolean enabled, double monthlyLimitCny,
                             Map<String, Map<String, Double>> per1mTokens) {

        public CostConfig {
            per1mTokens = per1mTokens == null ? Map.of() : Map.copyOf(per1mTokens);
        }

        public record Price(double inputPer1m, double outputPer1m) {
        }

        /** 按模型名解析单价：精确匹配 → 大小写不敏感包含匹配 → null（本地模型默认免费）。 */
        public Price resolve(String model) {
            if (!enabled || model == null || model.isBlank()) {
                return null;
            }
            Map<String, Double> exact = per1mTokens.get(model);
            if (exact != null) {
                return priceOf(exact);
            }
            String lower = model.toLowerCase();
            for (Map.Entry<String, Map<String, Double>> entry : per1mTokens.entrySet()) {
                if (lower.contains(entry.getKey().toLowerCase())) {
                    return priceOf(entry.getValue());
                }
            }
            return null;
        }

        private static Price priceOf(Map<String, Double> p) {
            return new Price(
                    p.getOrDefault("input", 0.0),
                    p.getOrDefault("output", 0.0));
        }
    }

    // ── 反馈 ──────────────────────────────────────────────────

    public Map<String, Object> addFeedback(String username, Map<String, Object> body) {
        List<Map<String, Object>> records = feedbackRecords(username);
        Map<String, Object> record = new LinkedHashMap<>(body == null ? Map.of() : body);
        // 归一化 rating：旧契约/存量数据用 up/down，统一为 like/dislike（与前端一致）
        String rating = str(record.get("rating"));
        if ("up".equals(rating)) {
            record.put("rating", "like");
        } else if ("down".equals(rating)) {
            record.put("rating", "dislike");
        }
        record.put("id", "fb_" + System.currentTimeMillis() + "_" + records.size());
        record.put("created_at", Instant.now().toString());
        record.put("username", username);
        records.add(record);
        saveFeedback(username, records);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("id", record.get("id"));
        return result;
    }

    public Map<String, Object> feedbackStats(String username) {
        List<Map<String, Object>> records = feedbackRecords(username);
        Map<String, Object> stats = new LinkedHashMap<>();
        if (records.isEmpty()) {
            stats.put("total", 0);
            stats.put("likes", 0);
            stats.put("dislikes", 0);
            stats.put("like_rate", 0);
            stats.put("avg_response_time", 0);
            stats.put("tool_usage", Map.of());
            stats.put("skill_usage", Map.of());
            stats.put("daily_counts", List.of());
            stats.put("response_time_trend", List.of());
        } else {
            int likes = 0, dislikes = 0;
            double totalTime = 0;
            int timeCount = 0;
            Map<String, Integer> toolUsage = new LinkedHashMap<>();
            Map<String, Integer> skillUsage = new LinkedHashMap<>();
            Map<String, Integer> daily = new LinkedHashMap<>();
            List<Map<String, Object>> timeTrend = new ArrayList<>();
            for (Map<String, Object> record : records) {
                String rating = str(record.get("rating"));
                if ("like".equals(rating) || "up".equals(rating)) likes++;
                if ("dislike".equals(rating) || "down".equals(rating)) dislikes++;
                Object rt = record.get("response_time");
                if (rt instanceof Number) {
                    totalTime += ((Number) rt).doubleValue();
                    timeCount++;
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("time", String.valueOf(record.getOrDefault("created_at", "")).substring(0, 16)
                            .replace("T", " "));
                    point.put("value", ((Number) rt).doubleValue());
                    timeTrend.add(point);
                }
                for (Object tool : listOf(record.get("tools_used"))) {
                    toolUsage.merge(String.valueOf(tool), 1, Integer::sum);
                }
                Object skill = record.get("skill_triggered");
                if (skill != null) {
                    skillUsage.merge(String.valueOf(skill), 1, Integer::sum);
                }
                String day = String.valueOf(record.getOrDefault("created_at", "")).substring(0, 10);
                daily.merge(day, 1, Integer::sum);
            }
            int rated = likes + dislikes;
            stats.put("total", records.size());
            stats.put("likes", likes);
            stats.put("dislikes", dislikes);
            stats.put("like_rate", rated > 0 ? Math.round(likes * 100.0 / rated * 10) / 10.0 : 0);
            stats.put("avg_response_time", timeCount > 0
                    ? Math.round(totalTime / timeCount * 100) / 100.0 : 0);
            stats.put("tool_usage", toolUsage);
            stats.put("skill_usage", skillUsage);
            List<Map<String, Object>> dailyCounts = new ArrayList<>();
            List<String> days = new ArrayList<>(daily.keySet());
            days.sort(String::compareTo);
            for (String day : days.subList(Math.max(0, days.size() - 14), days.size())) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("date", day);
                point.put("count", daily.get(day));
                dailyCounts.add(point);
            }
            stats.put("daily_counts", dailyCounts);
            stats.put("response_time_trend",
                    timeTrend.subList(Math.max(0, timeTrend.size() - 20), timeTrend.size()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("stats", stats);
        return result;
    }

    public Map<String, Object> feedbackRecords(String username, int limit, String rating) {
        List<Map<String, Object>> records = feedbackRecords(username);
        if (rating != null && !rating.isBlank()) {
            String normalized = normalizedRating(rating);
            records = records.stream()
                    .filter(r -> normalized.equals(normalizedRating(str(r.get("rating"))))).toList();
        }
        records = new ArrayList<>(records);
        // 输出归一化：存量 up/down → like/dislike，保证前端/统计契约一致
        records.forEach(r -> r.put("rating", normalizedRating(str(r.get("rating")))));
        records.sort((a, b) -> String.valueOf(b.getOrDefault("created_at", ""))
                .compareTo(String.valueOf(a.getOrDefault("created_at", ""))));
        if (records.size() > Math.max(1, limit)) {
            records = new ArrayList<>(records.subList(0, Math.max(1, limit)));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("records", records);
        result.put("count", records.size());
        return result;
    }

    // ── Skill 日志 ────────────────────────────────────────────

    public Map<String, Object> skillLogs(String username, int limit, String skillName) {
        List<Map<String, Object>> records = skillRecords(username);
        if (skillName != null && !skillName.isBlank()) {
            records = records.stream()
                    .filter(r -> skillName.equals(r.get("skill_name"))).toList();
        }
        records = new ArrayList<>(records);
        records.sort((a, b) -> String.valueOf(b.getOrDefault("triggered_at", ""))
                .compareTo(String.valueOf(a.getOrDefault("triggered_at", ""))));
        if (records.size() > Math.max(1, limit)) {
            records = new ArrayList<>(records.subList(0, Math.max(1, limit)));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("records", records);
        result.put("count", records.size());
        return result;
    }

    public Map<String, Object> skillStats(String username) {
        List<Map<String, Object>> records = skillRecords(username);
        Map<String, Integer> bySkill = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            bySkill.merge(String.valueOf(record.getOrDefault("skill_name", "未知")), 1, Integer::sum);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", records.size());
        stats.put("by_skill", bySkill);
        stats.put("recent", records.size() > 10 ? records.subList(0, 10) : records);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("stats", stats);
        return result;
    }

    // ── 工具调用 ──────────────────────────────────────────────

    public Map<String, Object> toolCalls(int limit, String toolName) {
        List<Map<String, Object>> records = toolRecords();
        if (toolName != null && !toolName.isBlank()) {
            records = records.stream()
                    .filter(r -> toolName.equals(r.get("tool_name"))).toList();
        }
        records = new ArrayList<>(records);
        records.sort((a, b) -> String.valueOf(b.getOrDefault("timestamp", ""))
                .compareTo(String.valueOf(a.getOrDefault("timestamp", ""))));
        if (records.size() > Math.max(1, limit)) {
            records = new ArrayList<>(records.subList(0, Math.max(1, limit)));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("records", records);
        result.put("count", records.size());
        return result;
    }

    public Map<String, Object> toolStats() {
        List<Map<String, Object>> records = toolRecords();
        Map<String, Object> byTool = new LinkedHashMap<>();
        int totalOk = 0;
        for (Map<String, Object> record : records) {
            String name = str(record.get("tool_name"));
            Object statObj = byTool.computeIfAbsent(name, k -> {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("count", 0);
                s.put("success", 0);
                s.put("avg_ms", 0);
                s.put("success_rate", 0);
                s.put("_total_ms", 0.0);
                return s;
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> stat = (Map<String, Object>) statObj;
            stat.put("count", ((Number) stat.get("count")).intValue() + 1);
            double duration = record.get("duration_ms") instanceof Number
                    ? ((Number) record.get("duration_ms")).doubleValue() : 0;
            stat.put("_total_ms", ((Number) stat.get("_total_ms")).doubleValue() + duration);
            if (Boolean.TRUE.equals(record.get("success"))) {
                stat.put("success", ((Number) stat.get("success")).intValue() + 1);
                totalOk++;
            }
        }
        for (Object statObj : byTool.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> stat = (Map<String, Object>) statObj;
            int count = ((Number) stat.get("count")).intValue();
            double totalMs = ((Number) stat.get("_total_ms")).doubleValue();
            int ok = ((Number) stat.get("success")).intValue();
            stat.put("avg_ms", count > 0 ? Math.round(totalMs / count * 10) / 10.0 : 0);
            stat.put("success_rate", count > 0 ? Math.round(ok * 100.0 / count * 10) / 10.0 : 0);
            stat.remove("_total_ms");
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", records.size());
        stats.put("by_tool", byTool);
        stats.put("success_rate", records.isEmpty() ? 0
                : Math.round(totalOk * 100.0 / records.size() * 10) / 10.0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("stats", stats);
        return result;
    }

    // ── R-10 用量/成本台账 ──────────────────────────────────

    /** 从完成的 trace 提取 llm_call span 的 token 用量写入台账。 */
    public void recordFromTrace(AgentRunTrace trace) {
        if (trace == null || trace.spans() == null) {
            return;
        }
        for (TraceSpan span : trace.spans()) {
            if (!"llm_call".equals(span.name())) {
                continue;
            }
            Object in = span.details().get("input_tokens");
            Object out = span.details().get("output_tokens");
            long input = in instanceof Number ? ((Number) in).longValue() : 0;
            long output = out instanceof Number ? ((Number) out).longValue() : 0;
            if (input <= 0 && output <= 0) {
                continue;
            }
            String model = str(span.details().get("model"));
            if (model == null || model.isBlank()) {
                model = trace.model();
            }
            recordUsage(trace.userId(), model, trace.channel(), input, output,
                    Instant.ofEpochMilli(span.startedAt()));
        }
    }

    public void recordUsage(String userId, String model, String channel,
                            long inputTokens, long outputTokens) {
        recordUsage(userId, model, channel, inputTokens, outputTokens, Instant.now());
    }

    public void recordUsage(String userId, String model, String channel,
                            long inputTokens, long outputTokens, Instant at) {
        if (inputTokens <= 0 && outputTokens <= 0) {
            return;
        }
        List<Map<String, Object>> records = usageRecords();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("user_id", userId == null ? "" : userId);
        record.put("model", model == null ? "" : model);
        record.put("channel", channel == null ? "" : channel);
        record.put("input_tokens", inputTokens);
        record.put("output_tokens", outputTokens);
        record.put("created_at", at.toString());
        records.add(record);
        saveUsage(records);
    }

    /** 用量/成本统计：按用户（空 = 全部）+ 月份聚合，含每日趋势与限额余量。 */
    public Map<String, Object> usageStats(String username, String month) {
        String monthPrefix = (month == null || month.isBlank())
                ? LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
                : month.trim();
        Map<String, Map<String, Object>> byModel = new LinkedHashMap<>();
        Map<String, Map<String, Object>> byDay = new LinkedHashMap<>();
        long totalInput = 0, totalOutput = 0;
        double totalCost = 0;
        int calls = 0;
        for (Map<String, Object> record : usageRecords()) {
            String user = str(record.get("user_id"));
            if (username != null && !username.isBlank() && !username.equals(user)) {
                continue;
            }
            String createdAt = str(record.get("created_at"));
            if (createdAt == null || !createdAt.startsWith(monthPrefix)) {
                continue;
            }
            String model = str(record.getOrDefault("model", "未知"));
            long input = num(record.get("input_tokens"));
            long output = num(record.get("output_tokens"));
            double cost = costOf(model, input, output);
            totalInput += input;
            totalOutput += output;
            totalCost += cost;
            calls++;
            accumulate(byModel.computeIfAbsent(model, k -> statEntry()), input, output, cost);
            String day = createdAt.length() >= 10 ? createdAt.substring(0, 10) : createdAt;
            accumulate(byDay.computeIfAbsent(day, k -> statEntry()), input, output, cost);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("month", monthPrefix);
        result.put("calls", calls);
        result.put("total_input_tokens", totalInput);
        result.put("total_output_tokens", totalOutput);
        result.put("total_tokens", totalInput + totalOutput);
        result.put("total_cost_cny", round2(totalCost));
        result.put("by_model", byModel);
        List<Map<String, Object>> daily = new ArrayList<>();
        List<String> days = new ArrayList<>(byDay.keySet());
        days.sort(String::compareTo);
        for (String day : days) {
            Map<String, Object> point = new LinkedHashMap<>(byDay.get(day));
            point.put("date", day);
            daily.add(point);
        }
        result.put("daily", daily);
        if (costConfig != null && costConfig.enabled() && costConfig.monthlyLimitCny() > 0) {
            result.put("monthly_limit_cny", costConfig.monthlyLimitCny());
            result.put("remaining_cny", round2(Math.max(0, costConfig.monthlyLimitCny() - totalCost)));
            result.put("quota_exceeded", totalCost >= costConfig.monthlyLimitCny());
        } else {
            result.put("monthly_limit_cny", 0);
            result.put("remaining_cny", 0);
            result.put("quota_exceeded", false);
        }
        return result;
    }

    /** 当前月累计成本是否已达月限额（未启用/无限额恒 false）。 */
    public boolean usageQuotaExceeded(String username) {
        if (costConfig == null || !costConfig.enabled() || costConfig.monthlyLimitCny() <= 0) {
            return false;
        }
        Map<String, Object> stats = usageStats(username, null);
        double cost = ((Number) stats.getOrDefault("total_cost_cny", 0)).doubleValue();
        return cost >= costConfig.monthlyLimitCny();
    }

    private double costOf(String model, long input, long output) {
        CostConfig.Price price = costConfig == null ? null : costConfig.resolve(model);
        if (price == null) {
            return 0;
        }
        return input / 1_000_000.0 * price.inputPer1m()
                + output / 1_000_000.0 * price.outputPer1m();
    }

    private static Map<String, Object> statEntry() {
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("calls", 0);
        stat.put("input_tokens", 0L);
        stat.put("output_tokens", 0L);
        stat.put("cost_cny", 0.0);
        return stat;
    }

    private static void accumulate(Map<String, Object> stat, long input, long output, double cost) {
        stat.put("calls", ((Number) stat.get("calls")).intValue() + 1);
        stat.put("input_tokens", ((Number) stat.get("input_tokens")).longValue() + input);
        stat.put("output_tokens", ((Number) stat.get("output_tokens")).longValue() + output);
        stat.put("cost_cny", round2(((Number) stat.get("cost_cny")).doubleValue() + cost));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private List<Map<String, Object>> usageRecords() {
        return recordsAt("analytics", "usage.json");
    }

    private void saveUsage(List<Map<String, Object>> records) {
        saveRecords(records, "analytics", "usage.json");
    }

    // ── 存储辅助 ──────────────────────────────────────────────

    private List<Map<String, Object>> feedbackRecords(String username) {
        return recordsAt("analytics", "feedback", username + ".json");
    }

    /** 旧契约 up/down → like/dislike 归一化（存量数据兼容）。 */
    private static String normalizedRating(String rating) {
        if ("up".equals(rating)) return "like";
        if ("down".equals(rating)) return "dislike";
        return rating == null ? "" : rating;
    }

    private void saveFeedback(String username, List<Map<String, Object>> records) {
        saveRecords(records, "analytics", "feedback", username + ".json");
    }

    private List<Map<String, Object>> skillRecords(String username) {
        return recordsAt("analytics", "skill_logs", username + ".json");
    }

    private List<Map<String, Object>> toolRecords() {
        return recordsAt("analytics", "tool_calls.json");
    }

    private List<Map<String, Object>> recordsAt(String... parts) {
        Map<String, Object> data = store.read(parts);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = data == null ? new ArrayList<>()
                : (List<Map<String, Object>>) data.getOrDefault("records", new ArrayList<>());
        return new ArrayList<>(records);
    }

    private void saveRecords(List<Map<String, Object>> records, String... parts) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", records);
        store.write(parts, data);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long num(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }
}

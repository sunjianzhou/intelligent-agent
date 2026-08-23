package com.intelligent.agent.web.domain.analytics;

import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
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

    public AnalyticsService(Path dataDir) {
        this.store = new JsonFileStore(dataDir);
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

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }
}

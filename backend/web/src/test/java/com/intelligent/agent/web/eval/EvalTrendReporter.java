package com.intelligent.agent.web.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * TD-08：把 {@code target/eval-results/eval-*.jsonl} 汇总成可读的 markdown 趋势报告。
 *
 * <p>{@link EvalSuite} 每次跑完自动刷新 {@code eval-trend.md}；也可以单独调用
 * {@link #writeReport(Path)} 对历史结果重新汇总（不依赖 Ollama）。
 * 报告内容：每次运行的用例数 / ok / error / 平均分 / 最低分 / 低分用例数，
 * 以及最近一次运行逐用例得分与相对上一轮的变化。
 */
public final class EvalTrendReporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter SHOW_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 质量线：低于该分数的用例在报告里单独计数（与 eval.min-score=7 对应）。 */
    private static final int QUALITY_LINE = 7;

    private EvalTrendReporter() {
    }

    /** 汇总目录下所有 {@code eval-*.jsonl}，返回 markdown 文本。 */
    public static String buildReport(Path resultsDir) throws IOException {
        List<Run> runs = loadRuns(resultsDir);

        StringBuilder sb = new StringBuilder();
        sb.append("# Eval 趋势报告（自动生成）\n\n");
        sb.append("生成时间：").append(LocalDateTime.now().format(SHOW_TS)).append("\n\n");
        sb.append("> 数据来源：`").append(resultsDir.toAbsolutePath())
                .append("` 下的 `eval-*.jsonl`；由 `EvalTrendReporter` 汇总，"
                        + "跑 `mvn -Peval test` 会自动刷新本文件。\n\n");

        if (runs.isEmpty()) {
            sb.append("_暂无 eval 结果文件。_\n");
            return sb.toString();
        }

        sb.append("## 历次运行\n\n");
        sb.append("| 运行时间 | 生效模型 | 用例 | ok | error | 平均分 | 最低分 | 低于 ")
                .append(QUALITY_LINE).append(" 分用例 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (Run r : runs) {
            sb.append("| ").append(r.timestamp().format(SHOW_TS))
                    .append(" | ").append(r.model().isBlank() ? "-" : r.model())
                    .append(" | ").append(r.cases().size())
                    .append(" | ").append(r.okCount())
                    .append(" | ").append(r.errorCount())
                    .append(" | ").append(fmt(r.average()))
                    .append(" | ").append(r.minScore() == null ? "-" : r.minScore())
                    .append(" | ").append(r.below(QUALITY_LINE))
                    .append(" |\n");
        }

        // 增量/调试运行（-Deval.cases=id1,id2）用例数远少于全量，直接取"最近一次"会误导；
        // 逐用例明细固定用最近一次**全量**运行（用例数最多的一轮）。
        int maxCases = runs.stream().mapToInt(r -> r.cases().size()).max().orElse(0);
        int latestFullIdx = runs.size() - 1;
        for (int i = runs.size() - 1; i >= 0; i--) {
            if (runs.get(i).cases().size() == maxCases) {
                latestFullIdx = i;
                break;
            }
        }
        Run latest = runs.get(latestFullIdx);
        Run previous = latestFullIdx > 0 ? runs.get(latestFullIdx - 1) : null;
        Map<String, CaseScore> prevById = new LinkedHashMap<>();
        if (previous != null) {
            previous.cases().forEach(c -> prevById.put(c.id(), c));
        }

        sb.append("\n## 最近一次全量运行逐用例（").append(latest.timestamp().format(SHOW_TS))
                .append("，").append(latest.cases().size()).append(" 例）\n\n");
        sb.append("| 用例 | 分类 | 得分 | 上一轮 | 变化 |\n");
        sb.append("|---|---|---|---|---|\n");
        for (CaseScore c : latest.cases()) {
            CaseScore p = prevById.get(c.id());
            String prev = p == null || p.score() == null ? "-" : String.valueOf(p.score());
            String delta = "-";
            if (p != null && p.score() != null && c.score() != null) {
                int d = c.score() - p.score();
                delta = d > 0 ? "+" + d : String.valueOf(d);
            }
            sb.append("| ").append(c.id())
                    .append(" | ").append(c.category() == null ? "-" : c.category())
                    .append(" | ").append(c.score() == null ? "error" : c.score())
                    .append(" | ").append(prev)
                    .append(" | ").append(delta)
                    .append(" |\n");
        }
        return sb.toString();
    }

    /** 汇总并覆盖写 {@code <resultsDir>/eval-trend.md} 与调用方指定文件。 */
    public static Path writeReport(Path resultsDir) throws IOException {
        return writeReport(resultsDir, resultsDir.resolve("eval-trend.md"));
    }

    /** 汇总并写入指定文件（父目录自动创建）。 */
    public static Path writeReport(Path resultsDir, Path outFile) throws IOException {
        String md = buildReport(resultsDir);
        if (outFile.getParent() != null) {
            Files.createDirectories(outFile.getParent());
        }
        Files.writeString(outFile, md, StandardCharsets.UTF_8);
        return outFile;
    }

    private static List<Run> loadRuns(Path resultsDir) throws IOException {
        if (!Files.isDirectory(resultsDir)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(resultsDir)) {
            files = stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("eval-") && n.endsWith(".jsonl");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
        List<Run> runs = new ArrayList<>();
        for (Path f : files) {
            Run run = loadRun(f);
            if (run != null) {
                runs.add(run);
            }
        }
        return runs;
    }

    private static Run loadRun(Path file) throws IOException {
        List<CaseScore> cases = new ArrayList<>();
        String model = "";
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<?, ?> json;
            try {
                json = MAPPER.readValue(line, Map.class);
            } catch (Exception e) {
                continue;   // 半截行（运行被中断）直接跳过
            }
            Object id = json.get("id");
            if (id == null) {
                continue;
            }
            Object scoreObj = json.get("score");
            Integer score = scoreObj instanceof Number n ? n.intValue() : null;
            Object category = json.get("category");
            Object status = json.get("status");
            if (model.isBlank() && json.get("model") instanceof String m && !m.isBlank()) {
                model = m;
            }
            cases.add(new CaseScore(String.valueOf(id),
                    category == null ? "" : String.valueOf(category),
                    score,
                    status == null ? "" : String.valueOf(status)));
        }
        if (cases.isEmpty()) {
            return null;
        }
        return new Run(timestampOf(file), model, cases);
    }

    private static LocalDateTime timestampOf(Path file) {
        String name = file.getFileName().toString();
        String raw = name.substring("eval-".length(), name.length() - ".jsonl".length());
        try {
            return LocalDateTime.parse(raw, FILE_TS);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    private record CaseScore(String id, String category, Integer score, String status) {
    }

    private record Run(LocalDateTime timestamp, String model, List<CaseScore> cases) {

        List<Integer> scores() {
            return cases.stream().filter(c -> c.score() != null).map(CaseScore::score).toList();
        }

        double average() {
            return scores().stream().mapToInt(Integer::intValue).average().orElse(0);
        }

        Integer minScore() {
            return scores().stream().min(Integer::compareTo).orElse(null);
        }

        long below(int threshold) {
            return scores().stream().filter(s -> s < threshold).count();
        }

        long okCount() {
            return cases.stream().filter(c -> "ok".equals(c.status())).count();
        }

        long errorCount() {
            return cases.stream().filter(c -> "error".equals(c.status())).count();
        }
    }
}

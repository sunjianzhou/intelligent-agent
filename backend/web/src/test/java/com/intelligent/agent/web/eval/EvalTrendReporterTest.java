package com.intelligent.agent.web.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TD-08：趋势汇总器的离线契约测试（不需要 Ollama）。
 * 用两份手写 JSONL 固定住"历次运行表 + 最近一次逐用例 + 环比变化"的输出形状。
 */
class EvalTrendReporterTest {

    @TempDir
    Path dir;

    private void writeRun(String name, String... lines) throws Exception {
        Files.write(dir.resolve(name), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aggregatesRunsAndComputesDeltas() throws Exception {
        writeRun("eval-20260817-203423.jsonl",
                "{\"id\":\"calc-001\",\"category\":\"tool\",\"score\":4,\"status\":\"ok\",\"model\":\"qwen2.5:7b\"}",
                "{\"id\":\"qa-001\",\"category\":\"knowledge\",\"score\":8,\"status\":\"ok\",\"model\":\"qwen2.5:7b\"}");
        writeRun("eval-20260821-215313.jsonl",
                "{\"id\":\"calc-001\",\"category\":\"tool\",\"score\":6,\"status\":\"ok\",\"model\":\"qwen2.5:7b\"}",
                "{\"id\":\"qa-001\",\"category\":\"knowledge\",\"score\":10,\"status\":\"ok\",\"model\":\"qwen2.5:7b\"}",
                "{\"id\":\"web-001\",\"category\":\"tool\",\"score\":null,\"status\":\"error\",\"model\":\"qwen2.5:7b\"}");

        String md = EvalTrendReporter.buildReport(dir);

        // 历次运行表：两行、平均分与最低分正确
        assertThat(md).contains("| 2026-08-17 20:34 | qwen2.5:7b | 2 | 2 | 0 | 6.00 | 4 | 1 |");
        assertThat(md).contains("| 2026-08-21 21:53 | qwen2.5:7b | 3 | 2 | 1 | 8.00 | 6 | 1 |");

        // 最近一次逐用例 + 环比：calc-001 4→6 记 +2，qa-001 8→10 记 +2
        assertThat(md).contains("| calc-001 | tool | 6 | 4 | +2 |");
        assertThat(md).contains("| qa-001 | knowledge | 10 | 8 | +2 |");
        // 失败用例以 error 呈现，且没有上一轮时环比记 -
        assertThat(md).contains("| web-001 | tool | error | - | - |");
    }

    @Test
    void emptyDirectoryProducesPlaceholderAndWriteCreatesFile() throws Exception {
        String md = EvalTrendReporter.buildReport(dir);
        assertThat(md).contains("_暂无 eval 结果文件。_");

        Path out = EvalTrendReporter.writeReport(dir, dir.resolve("nested/eval-trend.md"));
        assertThat(out).exists();
        assertThat(Files.readString(out, StandardCharsets.UTF_8)).contains("Eval 趋势报告");
    }

    /**
     * 本地存在历史 eval 结果时顺带刷新报告（干净检出/CI 直接跳过）。
     * <p>不跑 eval 就能重新汇总趋势的一键入口：
     * {@code mvnw test -Dtest=EvalTrendReporterTest}；
     * 追加 {@code -Deval.trend-out=<path>} 可同时把快照写到指定文件
     * （例如 {@code -Deval.trend-out=../../docs/eval-trend.md} 更新入库趋势文档）。
     */
    @Test
    void regeneratesTrendForLocalResultsWhenPresent() throws Exception {
        Path real = Path.of("target", "eval-results");
        if (!Files.isDirectory(real)) {
            return;
        }
        Path out = EvalTrendReporter.writeReport(real);

        assertThat(out).exists();
        assertThat(Files.readString(out, StandardCharsets.UTF_8)).contains("## 历次运行");

        String trendOut = System.getProperty("eval.trend-out", "");
        if (!trendOut.isBlank()) {
            Path snapshot = EvalTrendReporter.writeReport(real, Path.of(trendOut.trim()));
            assertThat(snapshot).exists();
        }
    }
}

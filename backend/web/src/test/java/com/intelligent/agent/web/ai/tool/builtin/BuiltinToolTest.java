package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.ai.tool.builtin.file.FileTool;
import com.intelligent.agent.web.ai.tool.builtin.file.FileEditTool;
import com.intelligent.agent.web.ai.tool.builtin.shell.ShellTool;
import com.intelligent.agent.web.ai.tool.builtin.web.WebSearchTool;
import com.intelligent.agent.web.ai.tool.ToolCall;
import com.intelligent.agent.web.ai.tool.ToolExecutionContext;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.ai.tool.ToolResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置工具测试（TODO-110 Task 1）：calculator / time / file / shell / web_search。
 */
class BuiltinToolTest {

    @TempDir
    Path tempDir;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    // ── Calculator ───────────────────────────────────────────

    @Test
    void calculatorEvaluatesSafeExpressions() {
        CalculatorTool tool = new CalculatorTool();

        assertThat(String.valueOf(tool.execute(Map.of("expression", "2 + 3 * 4")))).isEqualTo("14");
        assertThat(String.valueOf(tool.execute(Map.of("expression", "sqrt(16)")))).isEqualTo("4");
        assertThat(String.valueOf(tool.execute(Map.of("expression", "2 ^ 10")))).isEqualTo("1024");
    }

    @Test
    void calculatorRejectsUnsafeExpressions() {
        CalculatorTool tool = new CalculatorTool();

        assertThat(String.valueOf(tool.execute(Map.of("expression", "System.exit(0)"))))
                .contains("失败");
        assertThat(String.valueOf(tool.execute(Map.of("expression", "__import__"))))
                .contains("失败");
    }

    // ── Time ─────────────────────────────────────────────────

    @Test
    void timeToolReturnsCurrentTimeShapes() {
        TimeTool tool = new TimeTool();

        Map<String, Object> current = (Map<String, Object>) tool.execute(Map.of("action", "current_time"));
        assertThat(current).containsKeys("formatted", "date", "time");

        Map<String, Object> ts = (Map<String, Object>) tool.execute(Map.of("action", "timestamp"));
        assertThat(ts).containsKey("timestamp");
    }

    // ── File ─────────────────────────────────────────────────

    @Test
    void fileToolReadOnlyAndEditToolWritesWithinSafeDir() throws Exception {
        FileEditTool editTool = new FileEditTool(List.of(tempDir));
        Path file = tempDir.resolve("note.txt");

        Object written = editTool.execute(Map.of(
                "action", "write", "path", file.toString(), "content", "hello 文件"));
        assertThat(String.valueOf(written)).contains("changed=true");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("hello 文件");

        FileTool tool = new FileTool(List.of(tempDir));

        Map<String, Object> read = (Map<String, Object>) tool.execute(
                Map.of("action", "read", "path", file.toString()));
        assertThat(read.get("content")).isEqualTo("hello 文件");

        Map<String, Object> list = (Map<String, Object>) tool.execute(
                Map.of("action", "list", "path", tempDir.toString()));
        assertThat((List<?>) list.get("files")).extracting("name").contains("note.txt");

        Map<String, Object> info = (Map<String, Object>) tool.execute(
                Map.of("action", "info", "path", file.toString()));
        assertThat(info.get("exists")).isEqualTo(true);
    }

    @Test
    void fileToolRejectsWriteActionAsReadOnly() throws Exception {
        FileTool tool = new FileTool(List.of(tempDir));

        Object result = tool.execute(Map.of(
                "action", "write", "path", tempDir.resolve("x.txt").toString(), "content", "x"));

        assertThat(String.valueOf(result)).contains("不支持的操作");
    }

    @Test
    void fileToolPreviewShowsUnifiedDiffWithoutWriting() throws Exception {
        FileTool tool = new FileTool(List.of(tempDir));
        Path file = tempDir.resolve("preview.txt");
        Files.writeString(file, "line1\nline2\nline3\n", StandardCharsets.UTF_8);

        Map<String, Object> preview = (Map<String, Object>) tool.execute(Map.of(
                "action", "preview", "path", file.toString(),
                "preview_action", "write", "content", "line1\nline2 changed\nline3\n"));

        assertThat(preview.get("changed")).isEqualTo(true);
        assertThat(String.valueOf(preview.get("diff"))).contains("-line2");
        assertThat(String.valueOf(preview.get("diff"))).contains("+line2 changed");
        // 预览不落盘
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("line1\nline2\nline3\n");
    }

    @Test
    void fileToolRejectsPathOutsideSafeDir() throws Exception {
        FileTool tool = new FileTool(List.of(tempDir));

        Object result = tool.execute(Map.of(
                "action", "read", "path", tempDir.resolve("..").resolve("secret.txt").toString()));

        assertThat(String.valueOf(result)).contains("安全目录");
    }

    // ── Shell ────────────────────────────────────────────────

    @Test
    void shellToolAllowsWhitelistedAndRejectsDangerousCommands() {
        ShellTool tool = new ShellTool();

        Map<String, Object> allowed = (Map<String, Object>) tool.execute(
                Map.of("command", "echo hello"));
        assertThat(allowed.get("success")).isEqualTo(true);
        assertThat(String.valueOf(allowed.get("output"))).contains("hello");

        Map<String, Object> denied = (Map<String, Object>) tool.execute(
                Map.of("command", "rm -rf /"));
        assertThat(denied.get("success")).isEqualTo(false);

        Map<String, Object> sensitive = (Map<String, Object>) tool.execute(
                Map.of("command", "cat ~/.env"));
        assertThat(sensitive.get("success")).isEqualTo(false);
    }

    // ── WebSearch ────────────────────────────────────────────

    @Test
    void webSearchParsesHtmlResults() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "<html><body>"
                        + "<a class='result__a' href='https://example.com/a'>结果 A</a>"
                        + "<a class='result__snippet'>片段 A</a>"
                        + "<a class='result__a' href='https://example.com/b'>结果 B</a>"
                        + "<a class='result__snippet'>片段 B</a>"
                        + "</body></html>"));

        WebSearchTool tool = new WebSearchTool(
                server.url("/html/").toString() + "?q=", 10);

        List<Map<String, Object>> results = (List<Map<String, Object>>) tool.execute(
                Map.of("query", "java", "max_results", 5));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("title")).isEqualTo("结果 A");
        assertThat(results.get(0).get("url")).isEqualTo("https://example.com/a");
    }

    // ── ToolExecutor 端到端 ─────────────────────────────────

    @Test
    void toolExecutorRunsBuiltinCalculator() {
        ToolExecutor executor = new ToolExecutor(List.of(new CalculatorTool()));

        ToolResult result = executor.execute(
                ToolCall.of("calculator", Map.of("expression", "2 + 2")),
                ToolExecutionContext.of("u1", "user"));

        assertThat(result.status()).isEqualTo("success");
        assertThat(String.valueOf(result.data())).isEqualTo("4");
    }
}

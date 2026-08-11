package com.intelligent.agent.web.ai.tool;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具内核测试：requiredRole 校验、5 轮上限、
 * timeout 元数据执行，以及四种遗留文本解析（JSON / tag / fenced JSON / 纯文本）。
 */
class ToolExecutorTest {

    private static final AgentTool READ_TOOL = new AgentTool() {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("read_file", "读取文件", true, null, null);
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            return "file content";
        }
    };

    private static final AgentTool WRITE_TOOL = new AgentTool() {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("write_file", "写入文件", false, null, null);
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            return "written";
        }
    };

    private static final AgentTool ADMIN_TOOL = new AgentTool() {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("admin_op", "管理操作", false, "admin", null);
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            return "admin done";
        }
    };

    private static final AgentTool SLOW_TOOL = new AgentTool() {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("slow_tool", "慢工具", true, null, Duration.ofMillis(50));
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "too late";
        }
    };

    private final ToolExecutor executor =
            new ToolExecutor(List.of(READ_TOOL, WRITE_TOOL, ADMIN_TOOL, SLOW_TOOL));
    private final ToolCall writeCall = ToolCall.of("write_file", Map.of());
    private final TextToolCallParser parser = new TextToolCallParser();

    // ── 执行策略 ──────────────────────────────────────────────

    @Test
    void enforcesRequiredRole() {
        ToolExecutionContext normal = ToolExecutionContext.of("u1", "user");
        ToolExecutionContext admin = ToolExecutionContext.of("u1", "admin");

        assertThat(executor.execute(ToolCall.of("admin_op", Map.of()), normal).status())
                .isEqualTo("denied");
        assertThat(executor.execute(ToolCall.of("admin_op", Map.of()), admin).status())
                .isEqualTo("success");
    }

    @Test
    void enforcesFiveRoundLimit() {
        ToolExecutionContext ctx = ToolExecutionContext.of("u1", "user");
        for (int i = 0; i < 5; i++) {
            assertThat(executor.execute(ToolCall.of("read_file", Map.of()), ctx).status())
                    .isEqualTo("success");
        }
        assertThat(executor.execute(ToolCall.of("read_file", Map.of()), ctx).status())
                .isEqualTo("error");
    }

    @Test
    void enforcesTimeoutMetadata() {
        ToolExecutionContext ctx = ToolExecutionContext.of("u1", "user");
        assertThat(executor.execute(ToolCall.of("slow_tool", Map.of()), ctx).status())
                .isEqualTo("timeout");
    }

    @Test
    void returnsNotFoundForUnknownTool() {
        ToolExecutionContext ctx = ToolExecutionContext.of("u1", "user");
        assertThat(executor.execute(ToolCall.of("nope", Map.of()), ctx).status())
                .isEqualTo("not_found");
    }

    // ── 四种遗留文本解析 ───────────────────────────────────────

    @Test
    void parsesJsonFormat() {
        String text = "好的，我来处理：{\"tool\": \"read_file\", \"args\": {\"path\": \"a.txt\"}}";
        assertThat(parser.parse(text))
                .containsExactly(ToolCall.of("read_file", Map.of("path", "a.txt")));
    }

    @Test
    void parsesTagFormat() {
        String text = "<tool_call>{\"tool\": \"read_file\", \"args\": {\"path\": \"a.txt\"}}</tool_call>";
        assertThat(parser.parse(text))
                .containsExactly(ToolCall.of("read_file", Map.of("path", "a.txt")));
    }

    @Test
    void parsesTagAttributeFormat() {
        String text = "<tool_call {\"tool\": \"web_search\", \"args\": {\"query\": \"java\"}}>";
        assertThat(parser.parse(text))
                .containsExactly(ToolCall.of("web_search", Map.of("query", "java")));
    }

    @Test
    void parsesFencedJsonFormat() {
        String text = """
                我来调用工具：
                ```json
                {"tool": "read_file", "args": {"path": "b.txt"}}
                ```
                结果如上。
                """;
        assertThat(parser.parse(text))
                .containsExactly(ToolCall.of("read_file", Map.of("path", "b.txt")));
    }

    @Test
    void parsesPlainTextGemmaFormat() {
        String text = "<|tool_call>call:get_weather{query:\"shanghai\"}";
        assertThat(parser.parse(text))
                .containsExactly(ToolCall.of("get_weather", Map.of("query", "shanghai")));
    }

    @Test
    void mapsPlainTextAliasesToRealToolNames() {
        String text = "<|tool_call>call:local_file_read{path:\"x.txt\"}";
        assertThat(parser.parse(text))
                .containsExactly(ToolCall.of("FileTool", Map.of("path", "x.txt")));
    }

    @Test
    void deduplicatesRepeatedCalls() {
        String text = "<tool_call>{\"tool\": \"read_file\", \"args\": {\"path\": \"a.txt\"}}</tool_call>"
                + "{\"tool\": \"read_file\", \"args\": {\"path\": \"a.txt\"}}";
        assertThat(parser.parse(text)).hasSize(1);
    }
}

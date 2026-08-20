package com.intelligent.agent.web.integration.mcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * stdio 传输客户端测试：真实子进程（FakeMcpServer）上的
 * initialize（sessionId）/ tools/list / tools/call / 超时。
 */
class McpStdioClientTest {

    @Test
    void initializesListsAndCallsToolsOverStdio() throws Exception {
        Process process = spawn(List.of());
        try {
            McpStdioClient client = new McpStdioClient(
                    javaCommand(), List.of("-cp", classpath(),
                            FakeMcpServer.class.getName()),
                    Duration.ofSeconds(10));

            assertThat(client.initialize("2024-11-05")).isTrue();
            assertThat(client.sessionId()).isEqualTo("sess-fake-1");

            List<McpClient.McpToolInfo> tools = client.listTools();
            assertThat(tools).extracting(McpClient.McpToolInfo::name)
                    .contains("fake_echo");

            Map<String, Object> result = client.callTool(
                    "fake_echo", Map.of("text", "hi"));
            assertThat(result.get("success")).isEqualTo(true);
            assertThat(result.get("content")).isEqualTo("fake_echo:hi");

            client.close();
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void initializeTimesOutWhenServerNeverResponds() throws Exception {
        Process process = spawn(List.of("silent"));
        try {
            McpStdioClient client = new McpStdioClient(
                    javaCommand(), List.of("-cp", classpath(),
                            FakeMcpServer.class.getName(), "silent"),
                    Duration.ofMillis(300));

            assertThat(client.initialize("2024-11-05")).isFalse();
            client.close();
        } finally {
            process.destroyForcibly();
        }
    }

    private static Process spawn(List<String> extraArgs) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(javaCommand(), "-cp", classpath(),
                FakeMcpServer.class.getName());
        builder.command().addAll(extraArgs);
        builder.redirectErrorStream(false);
        return builder.start();
    }

    private static String javaCommand() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                windows ? "java.exe" : "java").toString();
    }

    private static String classpath() {
        return System.getProperty("java.class.path");
    }
}

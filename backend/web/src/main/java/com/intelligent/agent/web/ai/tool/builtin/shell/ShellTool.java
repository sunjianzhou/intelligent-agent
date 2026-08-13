package com.intelligent.agent.web.ai.tool.builtin.shell;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 安全 Shell 工具（TODO-110 Task 1）：白名单只读/信息类命令，拒绝破坏性与敏感路径。
 */
public class ShellTool implements AgentTool {

    private static final List<String> ALLOWED_PREFIXES = List.of(
            "echo", "date", "time", "whoami", "hostname", "uname",
            "ls", "dir", "pwd", "cat", "head", "tail", "wc", "find", "grep",
            "ps", "df", "du", "free", "uptime", "env", "printenv",
            "git log", "git status", "git diff", "git branch", "git show", "git tag");
    private static final Pattern SENSITIVE = Pattern.compile(
            "\\.env|id_rsa|id_ecdsa|\\.pem|\\.key|\\.p12|shadow|passwd|credentials|secret",
            Pattern.CASE_INSENSITIVE);

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "shell_tool", "执行安全的 Shell 命令（只读/信息类白名单）。"
                        + "参数: command(必填), timeout(秒,默认10,最大30)", true, null,
                Duration.ofSeconds(35),
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of("type", "string", "description", "要执行的命令"),
                                "timeout", Map.of("type", "integer",
                                        "description", "超时秒数，默认 10，最大 30")),
                        "required", List.of("command")));
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        String command = String.valueOf(arguments.getOrDefault("command", "")).trim();
        if (command.isEmpty()) {
            return Map.of("success", false, "error", "command 不能为空");
        }
        if (!isAllowed(command)) {
            return Map.of("success", false,
                    "error", "命令 '" + firstWord(command) + "' 不在允许列表中，仅支持只读/信息类命令");
        }
        if (SENSITIVE.matcher(command).find()) {
            return Map.of("success", false, "error", "命令包含敏感文件路径（.env / 密钥文件等），已拒绝执行");
        }
        int timeout = Math.min(Math.max(parseInt(arguments.get("timeout"), 10), 1), 30);
        try {
            Process process = new ProcessBuilder(command.split("\\s+")).start();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Map.of("success", false, "error", "命令超时（>" + timeout + "s）");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            String body = (output != null && !output.isBlank() ? output : error).trim();
            if (body.isEmpty()) {
                body = "(无输出)";
            }
            if (body.length() > 2000) {
                body = body.substring(0, 2000) + "\n...(输出已截断)";
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", process.exitValue() == 0);
            result.put("returncode", process.exitValue());
            result.put("output", body);
            return result;
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    private static boolean isAllowed(String command) {
        String c = command.toLowerCase();
        for (String prefix : ALLOWED_PREFIXES) {
            if (c.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String firstWord(String command) {
        return command.split("\\s+")[0];
    }

    private static int parseInt(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

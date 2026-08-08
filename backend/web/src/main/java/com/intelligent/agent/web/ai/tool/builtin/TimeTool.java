package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 时间工具（TODO-110 Task 1）：当前时间/格式化/时间戳。
 */
public class TimeTool implements AgentTool {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "time_tool", "获取当前时间。参数: action(current_time|formatted|timestamp,默认current_time),"
                        + " format(格式化模板,可选)", true, null, null);
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        String action = String.valueOf(arguments.getOrDefault("action", "current_time"));
        LocalDateTime now = LocalDateTime.now(ZONE);
        Map<String, Object> result = new LinkedHashMap<>();
        switch (action) {
            case "formatted": {
                String format = String.valueOf(arguments.getOrDefault("format", "yyyy-MM-dd HH:mm:ss"));
                result.put("formatted", now.format(DateTimeFormatter.ofPattern(format)));
                result.put("format", format);
                return result;
            }
            case "timestamp": {
                result.put("timestamp", Instant.now().toEpochMilli() / 1000.0);
                result.put("iso", Instant.now().toString());
                return result;
            }
            case "current_time":
            default: {
                result.put("timestamp", Instant.now().toString());
                result.put("formatted", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                result.put("date", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
                result.put("time", now.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                result.put("timezone", "Asia/Shanghai");
                return result;
            }
        }
    }
}

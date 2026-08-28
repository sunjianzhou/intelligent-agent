package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 工具模板（R-15）：掷骰子。
 * <p>
 * 复制本文件到 {@code backend/web/src/main/java/com/intelligent/agent/web/ai/tool/builtin/}
 * 并按「docs/tool-development.md」重命名、修改 description / parameters / execute 即可。
 * 安全要点：
 * <ul>
 *   <li>只读工具把 {@code readOnly} 置 true；有副作用必须置 false 并按需
 *       {@code approvalRequired=true}（如 file_edit_tool）；</li>
 *   <li>可能长时间运行的实现必须给 timeout（{@link ToolDefinition} 的 Duration 参数），
 *       超时由 {@link com.intelligent.agent.web.ai.tool.ToolExecutor} 强制中断；</li>
 *   <li>不要信任参数：所有外部输入按字符串/数值显式校验。</li>
 * </ul>
 */
public class DiceTool implements AgentTool {

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "roll_dice",
                "掷骰子。参数: sides(骰子面数，默认 6，范围 2~1000), count(个数，默认 1，范围 1~10)。"
                        + "返回每次的点数与总和。",
                true, null, Duration.ofSeconds(5),
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "sides", Map.of("type", "integer", "default", 6,
                                        "minimum", 2, "maximum", 1000),
                                "count", Map.of("type", "integer", "default", 1,
                                        "minimum", 1, "maximum", 10)),
                        "required", List.of()));
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        int sides = clampInt(arguments.get("sides"), 6, 2, 1000);
        int count = clampInt(arguments.get("count"), 1, 1, 10);
        java.util.List<Integer> rolls = new java.util.ArrayList<>(count);
        int total = 0;
        for (int i = 0; i < count; i++) {
            int value = ThreadLocalRandom.current().nextInt(1, sides + 1);
            rolls.add(value);
            total += value;
        }
        return Map.of("rolls", rolls, "total", total);
    }

    private static int clampInt(Object raw, int fallback, int min, int max) {
        if (!(raw instanceof Number number)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, number.intValue()));
    }
}

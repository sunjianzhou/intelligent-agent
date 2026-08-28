package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.ai.tool.ToolCall;
import com.intelligent.agent.web.ai.tool.ToolExecutionContext;
import com.intelligent.agent.web.ai.tool.ToolExecutor;
import com.intelligent.agent.web.ai.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 工具模板测试（R-15）：复制到 src/test 同名目录后按需修改。 */
class DiceToolTest {

    @Test
    void definitionExposesNameAndReadOnly() {
        DiceTool tool = new DiceTool();
        assertThat(tool.definition().name()).isEqualTo("roll_dice");
        assertThat(tool.definition().readOnly()).isTrue();
        assertThat(tool.definition().approvalRequired()).isFalse();
    }

    @Test
    void rollsWithinRangeAndSums() {
        DiceTool tool = new DiceTool();

        Map<String, Object> result = (Map<String, Object>) tool.execute(
                Map.of("sides", 6, "count", 2));

        List<?> rolls = (List<?>) result.get("rolls");
        assertThat(rolls).hasSize(2);
        assertThat(rolls).allSatisfy(v ->
                assertThat(((Number) v).intValue()).isBetween(1, 6));
        assertThat(((Number) result.get("total")).intValue())
                .isEqualTo(((Number) rolls.get(0)).intValue()
                        + ((Number) rolls.get(1)).intValue());
    }

    @Test
    void clampsOutOfRangeArguments() {
        DiceTool tool = new DiceTool();

        Map<String, Object> result = (Map<String, Object>) tool.execute(
                Map.of("sides", 9999, "count", 99));

        List<?> rolls = (List<?>) result.get("rolls");
        assertThat(rolls).hasSize(10); // count 被钳到 10
        assertThat(rolls).allSatisfy(v ->
                assertThat(((Number) v).intValue()).isBetween(1, 1000));
    }

    @Test
    void runsThroughToolExecutor() {
        ToolExecutor executor = new ToolExecutor(List.of(new DiceTool()));

        ToolResult result = executor.execute(
                ToolCall.of("roll_dice", Map.of("sides", 20)),
                ToolExecutionContext.of("u1", "user"));

        assertThat(result.status()).isEqualTo("success");
        assertThat((Map<?, ?>) result.data()).containsKey("total");
    }
}

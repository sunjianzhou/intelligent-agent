package com.intelligent.agent.web.ai.tool.builtin;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高级计算器契约（2026-08-15 补齐，对齐 Python AdvancedCalculatorTool）：
 * 纯表达式走基础计算器；带 convert_to 时输出换算结果。
 */
class AdvancedCalculatorToolTest {

    private final AdvancedCalculatorTool tool = new AdvancedCalculatorTool();

    @Test
    void plainExpressionDelegatesToCalculator() {
        Object result = tool.execute(Map.of("expression", "1+2*3"));
        assertThat(String.valueOf(result)).isEqualTo("7");
    }

    @Test
    void convertsKmToMiles() {
        Object result = tool.execute(Map.of("expression", "10", "convert_to", "km_to_miles"));

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) result;
        assertThat(map.get("conversion")).isEqualTo("km_to_miles");
        assertThat(((Number) map.get("original_value")).doubleValue()).isEqualTo(10.0);
        assertThat(((Number) map.get("converted_value")).doubleValue())
                .isCloseTo(6.21371, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void convertsCelsiusToFahrenheit() {
        Object result = tool.execute(Map.of("expression", "100", "convert_to", "celsius_to_fahrenheit"));

        Map<?, ?> map = (Map<?, ?>) result;
        assertThat(((Number) map.get("converted_value")).doubleValue())
                .isCloseTo(212.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    /** TD-20③：时间单位换算（unit-002/unit-004 的期望能力）。 */
    @Test
    void convertsHoursToMinutes() {
        Object result = tool.execute(Map.of("expression", "2.5", "convert_to", "hours_to_minutes"));

        Map<?, ?> map = (Map<?, ?>) result;
        assertThat(((Number) map.get("converted_value")).doubleValue()).isEqualTo(150.0);
    }

    @Test
    void convertsSecondsToHours() {
        Object result = tool.execute(Map.of("expression", "7200", "convert_to", "seconds_to_hours"));

        Map<?, ?> map = (Map<?, ?>) result;
        assertThat(((Number) map.get("converted_value")).doubleValue()).isEqualTo(2.0);
    }

    /** 2026-11-03 eval unit-003：克 ↔ 千克。 */
    @Test
    void convertsGramsToKilograms() {
        Object result = tool.execute(Map.of("expression", "5000", "convert_to", "g_to_kg"));

        Map<?, ?> map = (Map<?, ?>) result;
        assertThat(((Number) map.get("converted_value")).doubleValue()).isEqualTo(5.0);
        assertThat(String.valueOf(tool.definition().description())).contains("g_to_kg");
    }

    @Test
    void unsupportedConversionReturnsErrorText() {
        Object result = tool.execute(Map.of("expression", "5", "convert_to", "parsec_to_miles"));
        assertThat(String.valueOf(result)).contains("不支持的转换类型");
    }
}

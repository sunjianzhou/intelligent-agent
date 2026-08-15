package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 高级计算器工具（2026-08-15 补齐，对齐 Python AdvancedCalculatorTool）：
 * 普通数学表达式 + 单位换算（长度/温度/重量/面积）。
 */
public class AdvancedCalculatorTool implements AgentTool {

    private final CalculatorTool calculator = new CalculatorTool();

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "advanced_calculator", "高级计算器：支持数学表达式计算与单位换算。"
                        + "参数: expression(表达式,必填),"
                        + " convert_to(单位换算,可选: km_to_miles/miles_to_km/meters_to_feet/"
                        + "feet_to_meters/celsius_to_fahrenheit/fahrenheit_to_celsius/"
                        + "kg_to_pounds/pounds_to_kg/sqm_to_sqft/sqft_to_sqm)。",
                true, null, Duration.ofSeconds(30),
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "expression", Map.of("type", "string", "description", "数学表达式"),
                                "convert_to", Map.of("type", "string", "description", "单位换算标识")),
                        "required", List.of("expression")));
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        Object exprResult = calculator.execute(arguments);
        String convertTo = arguments.get("convert_to") == null
                ? "" : String.valueOf(arguments.get("convert_to")).trim();
        if (convertTo.isBlank()) {
            return exprResult;
        }
        double value;
        try {
            value = Double.parseDouble(String.valueOf(exprResult));
        } catch (NumberFormatException e) {
            return Map.of("original_value", exprResult,
                    "converted_value", null, "conversion", convertTo,
                    "error", "表达式结果不是数值，无法换算");
        }
        Double converted = convertUnits(value, convertTo);
        if (converted == null) {
            return "换算失败: 不支持的转换类型 " + convertTo
                    + "（可用: km_to_miles/miles_to_km/meters_to_feet/feet_to_meters/"
                    + "celsius_to_fahrenheit/fahrenheit_to_celsius/kg_to_pounds/"
                    + "pounds_to_kg/sqm_to_sqft/sqft_to_sqm）";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("original_value", value);
        result.put("converted_value", Math.round(converted * 1_000_000) / 1_000_000.0);
        result.put("conversion", convertTo);
        return result;
    }

    /** 单位换算表（与 Python AdvancedCalculatorTool._convert_units 一致）。 */
    private static Double convertUnits(double value, String conversion) {
        return switch (conversion) {
            case "km_to_miles" -> value * 0.621371;
            case "miles_to_km" -> value * 1.60934;
            case "meters_to_feet" -> value * 3.28084;
            case "feet_to_meters" -> value * 0.3048;
            case "celsius_to_fahrenheit" -> value * 9.0 / 5.0 + 32;
            case "fahrenheit_to_celsius" -> (value - 32) * 5.0 / 9.0;
            case "kg_to_pounds" -> value * 2.20462;
            case "pounds_to_kg" -> value * 0.453592;
            case "sqm_to_sqft" -> value * 10.7639;
            case "sqft_to_sqm" -> value * 0.092903;
            default -> null;
        };
    }
}

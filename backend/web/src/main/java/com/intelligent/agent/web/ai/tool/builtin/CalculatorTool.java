package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import net.objecthunter.exp4j.ExpressionBuilder;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 计算器工具（TODO-110 Task 1）：安全数学表达式求值（exp4j）。
 * 只允许数字/运算符/数学函数字符，拒绝危险函数名与 dunder。
 */
public class CalculatorTool implements AgentTool {

    private static final Pattern SAFE_CHARS = Pattern.compile("^[0-9a-z+\\-*/()^.,\\s]+$");
    private static final Pattern DANGEROUS = Pattern.compile(
            "__|import|open|exec|eval|compile|exit|system", Pattern.CASE_INSENSITIVE);

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "calculator", "执行数学计算。支持加减乘除、幂(^)、sqrt/sin/cos/tan/log/ln/abs 等。"
                        + "参数: expression(表达式,必填)", true, null, null,
                Map.of(
                        "type", "object",
                        "properties", Map.of("expression",
                                Map.of("type", "string",
                                        "description", "数学表达式，如 1+2*3")),
                        "required", List.of("expression")));
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        Object exprObj = arguments.get("expression");
        String expression = exprObj == null ? "" : String.valueOf(exprObj).trim();
        if (expression.isEmpty()) {
            return "计算失败: expression 不能为空";
        }
        String normalized = expression.toLowerCase()
                .replace("π", "pi")
                .replace("√(", "sqrt(")
                .replace("sqrt ", "sqrt(");
        if (normalized.contains("sqrt") && !normalized.contains("sqrt(")) {
            normalized = normalized.replace("sqrt", "sqrt(") + ")";
        }
        if (!SAFE_CHARS.matcher(normalized).matches() || DANGEROUS.matcher(normalized).find()) {
            return "计算失败: 不安全的表达式";
        }
        try {
            double result = new ExpressionBuilder(normalized).build().evaluate();
            return result == Math.rint(result) ? String.valueOf((long) result) : String.valueOf(result);
        } catch (Exception e) {
            return "计算失败: " + e.getMessage();
        }
    }
}

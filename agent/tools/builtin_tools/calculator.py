"""计算器工具"""
import math
import re
from typing import Union, Dict, Any
from tools.base_tool import BaseTool, ToolResult


class CalculatorTool(BaseTool):
    """数学计算器工具

    支持基本的数学运算：加、减、乘、除、幂、平方根、三角函数等
    """

    def __init__(self):
        description = "执行数学计算。支持加减乘除、幂运算、三角函数、对数等。"
        super().__init__(description=description)

    def execute(self, expression: str) -> Union[float, int, str]:
        """执行数学计算

        Args:
            expression: 数学表达式，例如 "2 + 2", "sqrt(16)", "sin(30)"

        Returns:
            计算结果
        """
        # 安全检查：移除危险字符
        expression = expression.strip()

        # 检查表达式是否安全
        if not self._is_safe_expression(expression):
            raise ValueError(f"不安全的表达式: {expression}")

        try:
            # 预处理表达式
            processed_expr = self._preprocess_expression(expression)

            # 计算表达式
            result = eval(processed_expr, {"__builtins__": {}}, self._get_math_functions())

            # 格式化结果
            if isinstance(result, (int, float)):
                if result == int(result):
                    return int(result)
                return round(result, 10)
            else:
                return str(result)

        except Exception as e:
            raise ValueError(f"计算失败: {expression}, 错误: {e}")

    def _is_safe_expression(self, expression: str) -> bool:
        """检查表达式是否安全"""
        # 允许的字符
        safe_pattern = r'^[0-9+\-*/().\s^√πesincostanloglnabs]+$'

        # 检查危险函数调用
        dangerous_patterns = [
            r'__.*__',
            r'import',
            r'open',
            r'exec',
            r'eval',
            r'compile',
            r'__import__',
        ]

        if not re.match(safe_pattern, expression, re.IGNORECASE):
            return False

        for pattern in dangerous_patterns:
            if re.search(pattern, expression, re.IGNORECASE):
                return False

        return True

    def _preprocess_expression(self, expression: str) -> str:
        """预处理表达式"""
        expr = expression.lower()

        # 替换常见数学符号
        replacements = {
            '^': '**',  # 幂运算
            '√': 'math.sqrt',
            'π': 'math.pi',
            'pi': 'math.pi',
            'e': 'math.e',
            'sin': 'math.sin',
            'cos': 'math.cos',
            'tan': 'math.tan',
            'log': 'math.log10',
            'ln': 'math.log',
            'abs': 'abs',
        }

        for old, new in replacements.items():
            expr = expr.replace(old, new)

        # 添加弧度转换（如果使用度数）
        trig_pattern = r'(sin|cos|tan)\(([^)]+)\)'

        def degrees_to_radians(match):
            func = match.group(1)
            angle = match.group(2)
            return f'math.{func}(math.radians({angle}))'

        # 假设输入是度数，自动转换
        expr = re.sub(trig_pattern, degrees_to_radians, expr)

        return expr

    def _get_math_functions(self) -> Dict[str, Any]:
        """获取数学函数"""
        return {
            'math': math,
            'sqrt': math.sqrt,
            'sin': math.sin,
            'cos': math.cos,
            'tan': math.tan,
            'log': math.log,
            'log10': math.log10,
            'exp': math.exp,
            'pow': math.pow,
            'abs': abs,
            'round': round,
            'pi': math.pi,
            'e': math.e,
        }


class AdvancedCalculatorTool(CalculatorTool):
    """高级计算器工具，支持单位转换和复杂计算"""

    def __init__(self):
        BaseTool.__init__(
            self,
            description="高级计算器，支持单位转换、统计计算和复杂数学运算。"
        )

    def execute(self, expression: str, convert_to: str = None) -> Union[float, int, str, Dict]:
        """执行计算，支持单位转换

        Args:
            expression: 数学表达式
            convert_to: 要转换的单位，如 "km_to_miles", "celsius_to_fahrenheit"

        Returns:
            计算结果，如果指定了单位转换，返回转换后的值
        """
        # 先计算结果
        result = super().execute(expression)

        # 如果需要单位转换
        if convert_to:
            converted = self._convert_units(float(result), convert_to)
            return {
                "original_value": result,
                "converted_value": converted,
                "conversion": convert_to
            }

        return result

    def _convert_units(self, value: float, conversion: str) -> float:
        """单位转换"""
        conversions = {
            # 长度
            "km_to_miles": lambda x: x * 0.621371,
            "miles_to_km": lambda x: x * 1.60934,
            "meters_to_feet": lambda x: x * 3.28084,
            "feet_to_meters": lambda x: x * 0.3048,

            # 温度
            "celsius_to_fahrenheit": lambda x: (x * 9/5) + 32,
            "fahrenheit_to_celsius": lambda x: (x - 32) * 5/9,

            # 重量
            "kg_to_pounds": lambda x: x * 2.20462,
            "pounds_to_kg": lambda x: x * 0.453592,

            # 面积
            "sqm_to_sqft": lambda x: x * 10.7639,
            "sqft_to_sqm": lambda x: x * 0.092903,
        }

        if conversion not in conversions:
            raise ValueError(f"不支持的转换类型: {conversion}")

        return round(conversions[conversion](value), 6)
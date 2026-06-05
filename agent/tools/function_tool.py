"""函数工具包装器"""
from typing import Callable, Any, Dict, Optional
import inspect
import asyncio
from loguru import logger
from tools.base_tool import BaseTool, ToolParameter, ToolSchema, AsyncBaseTool, ToolResult


class FunctionTool(BaseTool):
    """将普通函数包装为工具（修复版）"""

    def __init__(self, func: Callable, name: str = None, description: str = None, category: str = "general"):
        """初始化函数工具

        Args:
            func: 要包装的函数
            name: 工具名称，如果为None则使用函数名
            description: 工具描述，如果为None则使用函数文档字符串
            category: 工具类别
        """
        self.func = func
        self.is_async = asyncio.iscoroutinefunction(func)

        # 确定工具名称
        tool_name = name or func.__name__

        # 确定工具描述
        tool_description = description or func.__doc__ or f"函数工具: {tool_name}"

        # 调用父类初始化
        super().__init__(name=tool_name, description=tool_description, category=category)

        # 分析函数签名
        self.signature = inspect.signature(func)

    def _get_parameters(self):
        """从函数签名中提取参数信息"""
        parameters = []
        sig = inspect.signature(self.func)

        for param_name, param in sig.parameters.items():
            # 跳过self参数
            if param_name == 'self':
                continue

            # 跳过 *args 和 **kwargs
            if param.kind in (param.VAR_POSITIONAL, param.VAR_KEYWORD):
                continue

            # 获取参数类型
            param_type = str(param.annotation) if param.annotation != inspect.Parameter.empty else "str"

            # 简化的类型处理
            if "int" in param_type.lower():
                param_type = "int"
            elif "float" in param_type.lower():
                param_type = "float"
            elif "bool" in param_type.lower():
                param_type = "bool"
            elif "list" in param_type.lower():
                param_type = "list"
            elif "dict" in param_type.lower():
                param_type = "dict"
            else:
                param_type = "str"

            # 获取默认值
            default = param.default if param.default != inspect.Parameter.empty else None

            # 关键修复：只有当参数没有默认值时才是必需的
            # 即使默认值是None，也不表示参数是必需的
            required = param.default == inspect.Parameter.empty

            # 创建参数定义
            tool_param = ToolParameter(
                name=param_name,
                type=param_type,
                description=f"参数 {param_name}",
                required=required,
                default=default
            )
            parameters.append(tool_param)

        return parameters

    def execute(self, **kwargs) -> Any:
        """执行函数（修复版）"""
        try:
            logger.debug(f"执行函数工具: {self.name}, 参数: {kwargs}")

            # 如果函数是异步的
            if self.is_async:
                # 对于异步函数，在同步上下文中运行
                return asyncio.run(self._async_execute(**kwargs))
            else:
                # 同步函数
                return self.func(**kwargs)

        except TypeError as e:
            # 参数错误
            logger.error(f"函数工具 {self.name} 参数错误: {e}")
            raise ValueError(f"参数错误: {str(e)}")
        except Exception as e:
            logger.error(f"函数工具 {self.name} 执行失败: {e}")
            raise

    async def _async_execute(self, **kwargs) -> Any:
        """异步执行函数"""
        return await self.func(**kwargs)


class AsyncFunctionTool(AsyncBaseTool):
    """异步函数工具（修复版）"""

    def __init__(self, func: Callable, name: str = None, description: str = None, category: str = "general"):
        """初始化异步函数工具"""
        self.func = func
        self.is_async = asyncio.iscoroutinefunction(func)

        # 确定工具名称
        tool_name = name or func.__name__

        # 确定工具描述
        tool_description = description or func.__doc__ or f"异步函数工具: {tool_name}"

        # 调用父类初始化
        super().__init__(name=tool_name, description=tool_description, category=category)

        # 分析函数签名
        self.signature = inspect.signature(func)

    def _get_parameters(self):
        """从函数签名中提取参数信息"""
        parameters = []
        sig = inspect.signature(self.func)

        for param_name, param in sig.parameters.items():
            # 跳过self参数
            if param_name == 'self':
                continue

            # 跳过 *args 和 **kwargs
            if param.kind in (param.VAR_POSITIONAL, param.VAR_KEYWORD):
                continue

            # 获取参数类型
            param_type = str(param.annotation) if param.annotation != inspect.Parameter.empty else "str"

            # 简化的类型处理
            if "int" in param_type.lower():
                param_type = "int"
            elif "float" in param_type.lower():
                param_type = "float"
            elif "bool" in param_type.lower():
                param_type = "bool"
            elif "list" in param_type.lower():
                param_type = "list"
            elif "dict" in param_type.lower():
                param_type = "dict"
            else:
                param_type = "str"

            # 获取默认值
            default = param.default if param.default != inspect.Parameter.empty else None

            # 创建参数定义
            tool_param = ToolParameter(
                name=param_name,
                type=param_type,
                description=f"参数 {param_name}",
                required=param.default == inspect.Parameter.empty,
                default=default
            )
            parameters.append(tool_param)

        return parameters

    async def execute(self, **kwargs) -> Any:
        """异步执行函数"""
        try:
            logger.debug(f"执行异步函数工具: {self.name}, 参数: {kwargs}")

            if self.is_async:
                return await self.func(**kwargs)
            else:
                # 如果是普通函数，在异步环境中运行
                loop = asyncio.get_running_loop()
                return await loop.run_in_executor(None, lambda: self.func(**kwargs))

        except TypeError as e:
            logger.error(f"异步函数工具 {self.name} 参数错误: {e}")
            raise ValueError(f"参数错误: {str(e)}")
        except Exception as e:
            logger.error(f"异步函数工具 {self.name} 执行失败: {e}")
            raise
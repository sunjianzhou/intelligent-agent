"""工具基类定义"""
from abc import ABC, abstractmethod
from typing import Dict, Any, Optional, List, Callable
from pydantic import BaseModel, Field
from loguru import logger
import inspect
import json


class ToolParameter(BaseModel):
    """工具参数定义"""
    name: str
    type: str
    description: str
    required: bool = True
    default: Any = None


class ToolSchema(BaseModel):
    """工具模式定义"""
    name: str
    description: str
    parameters: List[ToolParameter]


class ToolResult(BaseModel):
    """工具执行结果"""
    success: bool
    data: Any
    error: Optional[str] = None
    execution_time: float = 0.0


class ToolError(Exception):
    """工具执行异常"""
    pass


class BaseTool(ABC):
    """工具基类"""

    def __init__(self, name: str = None, description: str = None, category: str = "general"):
        """初始化工具

        Args:
            name: 工具名称，如果为None则使用类名
            description: 工具描述，如果为None则使用类文档字符串
            category: 工具分类
        """
        self._name = name or self.__class__.__name__
        self._description = description or self.__doc__ or "没有描述"
        self._category = category
        self.parameters = self._get_parameters()
        self.requires_auth = False

    @property
    def name(self) -> str:
        """工具名称"""
        return self._name

    @property
    def description(self) -> str:
        """工具描述"""
        return self._description

    @property
    def category(self) -> str:
        """工具分类"""
        return self._category

    def _get_parameters(self) -> List[ToolParameter]:
        parameters = []
        execute_method = getattr(self, 'execute', None)
        if not execute_method:
            return parameters

        sig = inspect.signature(execute_method)

        for param_name, param in sig.parameters.items():
            if param_name == 'self':
                continue

            # ← 新增：跳过 *args 和 **kwargs
            if param.kind in (
                    inspect.Parameter.VAR_POSITIONAL,  # *args
                    inspect.Parameter.VAR_KEYWORD  # **kwargs
            ):
                continue

            param_type = str(param.annotation) if param.annotation != inspect.Parameter.empty else "str"
            if param_type == "<class 'str'>":
                param_type = "str"
            elif param_type == "<class 'int'>":
                param_type = "int"
            elif param_type == "<class 'float'>":
                param_type = "float"
            elif param_type == "<class 'bool'>":
                param_type = "bool"
            elif param_type == "<class 'list'>":
                param_type = "list"
            elif param_type == "<class 'dict'>":
                param_type = "dict"

            default = param.default if param.default != inspect.Parameter.empty else None
            required = param.default == inspect.Parameter.empty

            tool_param = ToolParameter(
                name=param_name,
                type=param_type,
                description=f"参数 {param_name}",
                required=required,
                default=default
            )
            parameters.append(tool_param)

        return parameters

    def get_schema(self) -> ToolSchema:
        """获取工具模式"""
        return ToolSchema(
            name=self.name,
            description=self.description,
            parameters=self.parameters
        )

    @abstractmethod
    def execute(self, **kwargs) -> Any:
        """执行工具

        Args:
            **kwargs: 工具参数

        Returns:
            执行结果
        """
        pass

    def _run(self, **kwargs) -> ToolResult:
        """运行工具（包装execute方法）"""
        import time

        start_time = time.time()

        try:
            # 验证参数
            self._validate_parameters(kwargs)

            # 执行工具
            result = self.execute(**kwargs)

            execution_time = time.time() - start_time

            return ToolResult(
                success=True,
                data=result,
                execution_time=execution_time
            )

        except Exception as e:
            execution_time = time.time() - start_time
            logger.error(f"工具 {self.name} 执行失败: {e}")

            return ToolResult(
                success=False,
                data=None,
                error=str(e),
                execution_time=execution_time
            )

    def _validate_parameters(self, provided_kwargs: Dict[str, Any]):
        """验证参数"""
        for param in self.parameters:
            if param.required and param.name not in provided_kwargs:
                raise ValueError(f"缺少必需参数: {param.name}")

        # 检查多余参数
        for provided_param in provided_kwargs.keys():
            if provided_param not in [p.name for p in self.parameters]:
                logger.debug(f"工具 {self.name} 收到未知参数: {provided_param}")

    def __call__(self, **kwargs) -> ToolResult:
        """使工具可调用"""
        return self._run(**kwargs)

    def __str__(self):
        return f"Tool(name={self.name}, description={self.description[:50]}...)"


class AsyncBaseTool(BaseTool):
    """异步工具基类"""

    def __init__(self, name: str = None, description: str = None, category: str = "general"):
        """初始化异步工具"""
        super().__init__(name, description, category)

    @abstractmethod
    async def execute(self, **kwargs) -> Any:
        """异步执行工具"""
        pass

    async def _run(self, **kwargs) -> ToolResult:
        """异步运行工具"""
        import time

        start_time = time.time()

        try:
            # 验证参数
            self._validate_parameters(kwargs)

            # 执行工具
            result = await self.execute(**kwargs)

            execution_time = time.time() - start_time

            return ToolResult(
                success=True,
                data=result,
                execution_time=execution_time
            )

        except Exception as e:
            execution_time = time.time() - start_time
            logger.error(f"异步工具 {self.name} 执行失败: {e}")

            return ToolResult(
                success=False,
                data=None,
                error=str(e),
                execution_time=execution_time
            )

    async def __call__(self, **kwargs) -> ToolResult:
        """异步调用"""
        return await self._run(**kwargs)
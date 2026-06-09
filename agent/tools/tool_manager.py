"""工具管理器"""
from typing import Dict, List, Any, Optional, Callable
from loguru import logger
from tools.base_tool import BaseTool, AsyncBaseTool, ToolSchema, ToolResult
from tools.function_tool import FunctionTool, AsyncFunctionTool
from tools.builtin_tools.database.database_tool import DatabaseTool


class ToolManager:
    """工具管理器"""

    def __init__(self):
        self.tools: Dict[str, BaseTool] = {}
        self.async_tools: Dict[str, AsyncBaseTool] = {}
        self.tool_categories: Dict[str, List[str]] = {}
        self.register_tool(DatabaseTool())

    def register_tool(self, tool: BaseTool, category: str = "general") -> None:
        """注册工具

        Args:
            tool: 工具实例
            category: 工具分类
        """
        if isinstance(tool, AsyncBaseTool):
            self.async_tools[tool.name] = tool
            logger.info(f"注册异步工具: {tool.name} (分类: {category})")
        else:
            self.tools[tool.name] = tool
            logger.info(f"注册工具: {tool.name} (分类: {category})")

        # 添加到分类
        if category not in self.tool_categories:
            self.tool_categories[category] = []

        if tool.name not in self.tool_categories[category]:
            self.tool_categories[category].append(tool.name)

    def register_function(self, func: Callable, name: str = None,
                          description: str = None, category: str = "general") -> None:
        """注册函数为工具

        Args:
            func: 函数
            name: 工具名称，如果为None则使用函数名
            description: 工具描述
            category: 工具分类
        """
        import asyncio

        if asyncio.iscoroutinefunction(func):
            tool = AsyncFunctionTool(func=func, name=name, description=description)
        else:
            tool = FunctionTool(func=func, name=name, description=description)

        self.register_tool(tool, category)

    def get_tool(self, tool_name: str) -> Optional[BaseTool]:
        """获取工具

        Args:
            tool_name: 工具名称

        Returns:
            工具实例，如果不存在返回None
        """
        if tool_name in self.tools:
            return self.tools[tool_name]
        elif tool_name in self.async_tools:
            return self.async_tools[tool_name]
        else:
            logger.warning(f"工具不存在: {tool_name}")
            return None

    def get_tool_schema(self, tool_name: str) -> Optional[ToolSchema]:
        """获取工具模式

        Args:
            tool_name: 工具名称

        Returns:
            工具模式，如果工具不存在返回None
        """
        tool = self.get_tool(tool_name)
        if tool:
            return tool.get_schema()
        return None

    def get_all_tools(self) -> Dict[str, BaseTool]:
        """获取所有工具"""
        all_tools = {}
        all_tools.update(self.tools)
        all_tools.update(self.async_tools)
        return all_tools

    def get_all_schemas(self) -> Dict[str, ToolSchema]:
        """获取所有工具模式"""
        schemas = {}
        for name, tool in self.get_all_tools().items():
            schemas[name] = tool.get_schema()
        return schemas

    def get_tools_by_category(self, category: str) -> List[str]:
        """获取分类下的工具名称列表

        Args:
            category: 工具分类

        Returns:
            工具名称列表
        """
        return self.tool_categories.get(category, [])

    def get_categories(self) -> List[str]:
        """获取所有分类"""
        return list(self.tool_categories.keys())

    def execute_tool(self, tool_name: str, **kwargs) -> ToolResult:
        """执行工具

        Args:
            tool_name: 工具名称
            **kwargs: 工具参数

        Returns:
            执行结果
        """
        tool = self.get_tool(tool_name)
        if not tool:
            return ToolResult(
                success=False,
                data=None,
                error=f"工具不存在: {tool_name}"
            )

        if isinstance(tool, AsyncBaseTool):
            logger.warning(f"工具 {tool_name} 是异步工具，但使用了同步执行方式")
            return ToolResult(
                success=False,
                data=None,
                error=f"工具 {tool_name} 是异步工具，请使用异步执行"
            )

        logger.info(f"执行工具: {tool_name} 参数: {kwargs}")
        return tool(**kwargs)

    async def execute_tool_async(self, tool_name: str, **kwargs) -> ToolResult:
        """异步执行工具

        Args:
            tool_name: 工具名称
            **kwargs: 工具参数

        Returns:
            执行结果
        """
        tool = self.get_tool(tool_name)
        if not tool:
            return ToolResult(
                success=False,
                data=None,
                error=f"工具不存在: {tool_name}"
            )

        if isinstance(tool, AsyncBaseTool):
            logger.info(f"异步执行工具: {tool_name} 参数: {kwargs}")
            return await tool(**kwargs)
        else:
            logger.info(f"同步执行工具: {tool_name} 参数: {kwargs}")
            return tool(**kwargs)

    def list_tools(self) -> str:
        """列出所有工具（用于提示词）"""
        tools_info = []

        for category, tool_names in self.tool_categories.items():
            tools_info.append(f"\n{category.upper()} 工具:")
            for tool_name in tool_names:
                tool = self.get_tool(tool_name)
                if tool:
                    tools_info.append(f"  - {tool.name}: {tool.description}")

        return "\n".join(tools_info)

    def clear_tools(self) -> None:
        """清除所有工具"""
        self.tools.clear()
        self.async_tools.clear()
        self.tool_categories.clear()
        logger.info("已清除所有工具")


# 全局默认实例（供 CLI / 测试等单用户场景使用）
# IntelligentAgent 会调用 ToolManager() 创建自己的专属实例，不使用此全局对象
tool_manager = ToolManager()

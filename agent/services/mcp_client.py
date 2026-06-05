"""MCP Client 管理器（兼容 mcp 1.27.0）"""
import asyncio
import json
from typing import Any, Dict, List, Optional
from loguru import logger

from tools.base_tool import BaseTool, ToolResult, ToolParameter


class MCPToolWrapper(BaseTool):
    """把 MCP 工具包装成 BaseTool"""

    def __init__(self, tool_name: str, tool_desc: str,
                 parameters: List[ToolParameter], call_func):
        self._name        = tool_name
        self._description = tool_desc
        self.parameters   = parameters
        self._call_func   = call_func  # 异步调用函数

    @property
    def name(self) -> str:
        return self._name

    @property
    def description(self) -> str:
        return self._description

    def execute(self, **kwargs) -> Any:
        try:
            loop = asyncio.get_running_loop()
            # 已有运行中的事件循环（FastAPI 服务内），通过线程安全桥调用
            import concurrent.futures
            future = asyncio.run_coroutine_threadsafe(
                self._call_func(self._name, kwargs), loop
            )
            return future.result(timeout=60)
        except RuntimeError:
            # 无运行中的事件循环（CLI/测试），直接 run
            return asyncio.run(self._call_func(self._name, kwargs))


class MCPServerConnection:
    """单个 MCP Server 的长驻连接，在独立 task 中保持生命周期"""

    def __init__(self, server_name: str, command: str,
                 args: List[str], env: Optional[Dict[str, str]] = None):
        self.server_name = server_name
        self.command     = command
        self.args        = args
        self.env         = env or {}
        self.tools: List[MCPToolWrapper] = []

        # 用于跨 task 通信
        self._call_queue:   asyncio.Queue = None
        self._result_queues: Dict[int, asyncio.Queue] = {}
        self._call_id       = 0
        self._ready_event:  asyncio.Event = None
        self._task          = None
        self._error         = None

    async def start(self):
        """在当前事件循环里启动后台 task"""
        self._call_queue  = asyncio.Queue()
        self._ready_event = asyncio.Event()
        self._task = asyncio.create_task(self._run())
        # 等待连接就绪或失败
        await asyncio.wait_for(self._ready_event.wait(), timeout=30)
        if self._error:
            raise RuntimeError(self._error)

    async def _run(self):
        """后台 task：持有 MCP 连接生命周期"""
        try:
            import os
            from mcp import ClientSession, StdioServerParameters
            from mcp.client.stdio import stdio_client
        except ImportError as e:
            self._error = str(e)
            if self._ready_event:
                self._ready_event.set()
            logger.error(f"MCP [{self.server_name}] 依赖缺失: {e}")
            return

        full_env = {**os.environ, **self.env}
        params   = StdioServerParameters(
            command=self.command, args=self.args, env=full_env
        )
        try:
            async with stdio_client(params) as (read, write):
                async with ClientSession(read, write) as session:
                    await session.initialize()

                    # 获取工具列表
                    tools_resp = await session.list_tools()
                    self._register_tools(tools_resp.tools, session)
                    self._ready_event.set()

                    logger.info(f"MCP [{self.server_name}] 连接就绪，"
                                f"工具数: {len(self.tools)}")

                    # 持续处理工具调用请求
                    while True:
                        try:
                            call_id, tool_name, kwargs = \
                                await asyncio.wait_for(
                                    self._call_queue.get(), timeout=1.0
                                )
                        except asyncio.TimeoutError:
                            continue

                        try:
                            result = await session.call_tool(
                                tool_name, arguments=kwargs
                            )
                            contents = []
                            for block in result.content:
                                if hasattr(block, 'text'):
                                    contents.append(block.text)
                                elif hasattr(block, 'data'):
                                    contents.append(str(block.data))
                            value = "\n".join(contents) or "执行成功"
                        except Exception as e:
                            value = Exception(f"工具调用失败: {e}")

                        if call_id in self._result_queues:
                            await self._result_queues[call_id].put(value)

        except Exception as e:
            self._error = str(e)
            self._ready_event.set()
            logger.error(f"MCP [{self.server_name}] 连接失败: {e}")

    def _register_tools(self, mcp_tools, session):
        """注册工具，call_func 通过 queue 与后台 task 通信"""
        for t in mcp_tools:
            props    = {}
            required = []
            schema   = t.inputSchema if hasattr(t, 'inputSchema') else {}
            for name, prop in schema.get("properties", {}).items():
                props[name] = prop
                if name in schema.get("required", []):
                    required.append(name)

            parameters = [
                ToolParameter(
                    name        = name,
                    type        = prop.get("type", "string"),
                    description = prop.get("description", f"参数 {name}"),
                    required    = name in required,
                    default     = prop.get("default"),
                )
                for name, prop in props.items()
            ]

            wrapper = MCPToolWrapper(
                tool_name  = t.name,
                tool_desc  = t.description or t.name,
                parameters = parameters,
                call_func  = self._call_tool,
            )
            self.tools.append(wrapper)

    async def _call_tool(self, tool_name: str, kwargs: dict) -> Any:
        """通过 queue 向后台 task 发送调用请求"""
        self._call_id += 1
        call_id = self._call_id
        result_q: asyncio.Queue = asyncio.Queue()
        self._result_queues[call_id] = result_q

        await self._call_queue.put((call_id, tool_name, kwargs))
        result = await asyncio.wait_for(result_q.get(), timeout=60)
        del self._result_queues[call_id]

        if isinstance(result, Exception):
            raise result
        return result

    async def stop(self):
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass


class MCPClientManager:
    """管理多个 MCP Server"""

    def __init__(self):
        self._connections: Dict[str, MCPServerConnection] = {}

    async def connect_server(self, server_name: str, command: str,
                             args: List[str],
                             env: Optional[Dict[str, str]] = None) -> List[str]:
        conn = MCPServerConnection(server_name, command, args, env)
        await conn.start()
        self._connections[server_name] = conn
        return [t.name for t in conn.tools]

    def register_to_tool_manager(self, server_name: str,
                                  tool_manager,
                                  category: str = None) -> int:
        if server_name not in self._connections:
            return 0
        cat   = category or server_name
        count = 0
        for wrapper in self._connections[server_name].tools:
            tool_manager.register_tool(wrapper, cat)
            count += 1
        return count

    async def close_all(self):
        for conn in self._connections.values():
            await conn.stop()
        self._connections.clear()


mcp_manager = MCPClientManager()
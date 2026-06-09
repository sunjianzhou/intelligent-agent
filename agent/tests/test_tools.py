#!/usr/bin/env python3
"""测试工具框架"""
import asyncio
import pytest
from tools.tool_manager import ToolManager
from core.agent import IntelligentAgent


async def test_tool_manager():
    """测试工具管理器（使用独立实例，不依赖全局状态）"""
    from tools.builtin_tools.calculator import CalculatorTool
    from tools.builtin_tools.time_tool import TimeTool
    from tools.builtin_tools.file_tool import FileTool

    tm = ToolManager()
    tm.register_tool(CalculatorTool())
    tm.register_tool(TimeTool())
    tm.register_tool(FileTool())

    print("🔧 测试工具管理器...")
    print("=" * 60)

    tools = tm.get_all_tools()
    print(f"已注册工具数量: {len(tools)}")
    for name, tool in tools.items():
        print(f"\n工具: {name}")
        print(f"  描述: {tool.description}")
        print(f"  参数: {[p.name for p in tool.parameters]}")

    print("\n" + "=" * 60)
    print("测试工具执行...")

    print("\n测试 CalculatorTool:")
    result = tm.execute_tool("CalculatorTool", expression="2 + 2 * 3")
    assert result is not None and result.success, f"CalculatorTool 失败: {result}"
    print(f"结果: {result.data} (成功: {result.success})")

    print("\n测试 TimeTool:")
    result = tm.execute_tool("TimeTool", action="current_time")
    assert result is not None and result.success, f"TimeTool 失败: {result}"
    print(f"当前时间: {result.data.get('time')}")

    # 测试异步工具执行
    print("\n" + "=" * 60)
    print("测试异步工具执行...")

    # 测试文件工具（需要安全目录）
    import tempfile
    import os

    temp_dir = tempfile.mkdtemp()
    test_file = os.path.join(temp_dir, "test.txt")

    print(f"\n测试目录: {temp_dir}")

    # 创建文件
    result = tm.execute_tool("FileTool",
                             action="create",
                             path=test_file,
                             content="Hello, World!")
    assert result is not None and result.success, f"FileTool create 失败: {result}"
    print(f"创建文件: {result.success}, 消息: {result.data.get('message')}")

    # 读取文件
    result = tm.execute_tool("FileTool",
                             action="read",
                             path=test_file,
                             mode="text")
    assert result is not None and result.success, f"FileTool read 失败: {result}"
    print(f"读取文件: {result.data.get('content')}")

    # 清理
    import shutil
    shutil.rmtree(temp_dir)


async def test_agent_with_tools():
    """测试带工具的智能体（集成测试，需要实时 LLM）"""
    pytest.skip("integration test: requires live LLM and deprecated agent.test_connection() API")

    # 获取可用工具
    tools_info = agent.get_available_tools()
    print(f"智能体可用工具: {tools_info['count']} 个")

    # 测试对话
    test_cases = [
        ("计算 2 + 2 等于多少？", True),
        ("当前时间是什么？", True),
        ("你好，介绍一下你自己", False),  # 不使用工具
    ]

    for message, use_tools in test_cases:
        print(f"\n问题: {message}")
        print(f"使用工具: {use_tools}")
        print("-" * 40)

        response = await agent.chat(message, use_tools=use_tools)
        print(f"回答: {response[:200]}...")

        # 等待一下
        await asyncio.sleep(1)

    # 测试复杂的工具调用场景
    print("\n" + "=" * 60)
    print("测试复杂工具调用...")

    complex_queries = [
        "先计算 15 * 3 的结果，然后告诉我现在的时间",
        "创建一个测试文件，内容为'Hello AI'，然后读取它",
    ]

    for query in complex_queries[:1]:  # 只测试第一个
        print(f"\n复杂查询: {query}")

        response = await agent.chat_with_tools(query)
        print(f"回答: {response[:300]}...")

    print("\n" + "=" * 60)
    print("测试完成")


def test_tool_extraction():
    """测试工具调用提取"""
    print("\n" + "=" * 60)
    print("测试工具调用提取...")

    agent = IntelligentAgent()

    test_texts = [
        """这是一个正常的回答，没有工具调用。""",
        """我需要计算一些东西。
<tool_call>
{
  "tool": "CalculatorTool",
  "args": {
    "expression": "2 + 2"
  }
}
</tool_call>""",
        """我需要多个工具。
<tool_call>
{"tool": "TimeTool", "args": {"action": "current_time"}}
</tool_call>
<tool_call>
{"tool": "CalculatorTool", "args": {"expression": "sqrt(16)"}}
</tool_call>""",
    ]

    for i, text in enumerate(test_texts, 1):
        print(f"\n测试 {i}:")
        tool_calls = agent._extract_tool_calls(text)
        print(f"提取到 {len(tool_calls)} 个工具调用")
        for call in tool_calls:
            print(f"  工具: {call['tool']}, 参数: {call['args']}")


async def main():
    """主测试函数"""
    print("开始测试工具框架...")

    # 测试工具管理器
    await test_tool_manager()

    # 测试工具调用提取
    test_tool_extraction()

    # 测试带工具的智能体
    await test_agent_with_tools()

    print("\n✅ 所有测试完成")


if __name__ == "__main__":
    asyncio.run(main())
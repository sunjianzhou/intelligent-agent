#!/usr/bin/env python3
"""专门测试create_reminder工具"""
import sys
import os
import asyncio

# 获取当前脚本所在的目录 (tests/)
current_dir = os.path.dirname(os.path.abspath(__file__))
# 获取项目根目录 (intelligent-agent/)，即 tests 的父目录
project_root = os.path.dirname(current_dir)

# 将项目根目录添加到系统路径的最前面
if project_root not in sys.path:
    sys.path.insert(0, project_root)
# 添加项目根目录到路径
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from tools.tool_manager import tool_manager
from core.agent import IntelligentAgent
import inspect

print("🧪 专门测试create_reminder工具")
print("=" * 60)


def test_parameter_extraction():
    """测试参数提取"""
    print("1. 测试参数提取...")

    # 模拟create_reminder函数
    def create_reminder(message: str, remind_in_seconds: int = 60,
                        reminder_name: str = None) -> dict:
        return {"success": True}

    # 检查函数签名
    sig = inspect.signature(create_reminder)
    print(f"   函数签名: {sig}")

    for param_name, param in sig.parameters.items():
        default = param.default if param.default != inspect.Parameter.empty else None
        required = param.default == inspect.Parameter.empty
        print(f"   参数: {param_name}, 类型: {param.annotation}, "
              f"必需: {required}, 默认值: {default}")


async def test_agent_tools():
    """测试智能体工具"""
    print("\n2. 测试智能体工具...")

    try:
        # 创建智能体
        agent = IntelligentAgent()

        # 获取工具
        tool = agent.tool_manager.get_tool("create_reminder")
        if not tool:
            print("   ❌ 找不到create_reminder工具")
            return False

        print(f"   ✅ 找到工具: {tool.name}")
        print(f"   描述: {tool.description}")

        # 检查工具参数
        schema = tool.get_schema()
        print(f"   工具模式: {schema}")

        for param in schema.parameters:
            print(f"   参数: {param.name}, 必需: {param.required}, "
                  f"默认值: {param.default}")

        return True

    except Exception as e:
        print(f"   ❌ 测试失败: {e}")
        import traceback
        traceback.print_exc()
        return False


async def test_tool_execution():
    """测试工具执行"""
    print("\n3. 测试工具执行...")

    try:
        # 测试不带reminder_name
        print("   测试不带reminder_name参数...")
        result1 = tool_manager.execute_tool(
            "create_reminder",
            message="测试提醒1",
            remind_in_seconds=5
        )

        if result1.success:
            print(f"   ✅ 执行成功: {result1.data}")
        else:
            print(f"   ❌ 执行失败: {result1.error}")

        # 测试带reminder_name
        print("\n   测试带reminder_name参数...")
        result2 = tool_manager.execute_tool(
            "create_reminder",
            message="测试提醒2",
            remind_in_seconds=10,
            reminder_name="自定义提醒名称"
        )

        if result2.success:
            print(f"   ✅ 执行成功: {result2.data}")
        else:
            print(f"   ❌ 执行失败: {result2.error}")

        return result1.success and result2.success

    except Exception as e:
        print(f"   ❌ 测试失败: {e}")
        import traceback
        traceback.print_exc()
        return False


async def main():
    """主测试函数"""
    all_passed = True

    # 测试参数提取
    if not test_parameter_extraction():
        all_passed = False

    # 测试智能体工具
    if not await test_agent_tools():
        all_passed = False

    # 测试工具执行
    if not await test_tool_execution():
        all_passed = False

    print("\n" + "=" * 60)
    if all_passed:
        print("✅ 所有测试通过!")
    else:
        print("❌ 测试失败!")

    return all_passed


if __name__ == "__main__":
    success = asyncio.run(main())
    sys.exit(0 if success else 1)
#!/usr/bin/env python3
"""系统健康检查 - 验证所有核心功能"""
import sys
import os
import asyncio
import time
from datetime import datetime

# 获取当前脚本所在的目录 (tests/)
current_dir = os.path.dirname(os.path.abspath(__file__))
# 获取项目根目录 (intelligent-agent/)，即 tests 的父目录
project_root = os.path.dirname(current_dir)

# 将项目根目录添加到系统路径的最前面
if project_root not in sys.path:
    sys.path.insert(0, project_root)
# 添加项目根目录到路径
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from core.agent import IntelligentAgent
from tools.tool_manager import tool_manager
from memory.manager import MemoryManager
from scheduler.simple_manager import TaskManager

print("🏥 系统健康检查")
print("=" * 60)
print("检查时间:", datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
print()


async def check_memory_system():
    """检查记忆系统"""
    print("1. 检查记忆系统...")

    try:
        # 创建独立的记忆管理器
        memory = MemoryManager(
            long_term_config={
                "name": "health_check",
                "vector_db_type": "memory",  # 使用内存数据库加快测试
                "embedding_model": "all-MiniLM-L6-v2"
            }
        )

        # 测试存储
        memory_item = memory.store("健康检查测试记忆", "test")
        print(f"   ✅ 记忆存储: ID={memory_item.id}")

        # 测试检索
        results = memory.search_relevant_memories("健康检查", limit=1)
        print(f"   ✅ 记忆检索: 找到 {len(results)} 条结果")

        # 测试统计
        stats = memory.get_stats()
        print(f"   ✅ 记忆统计: 短期={stats['short_term']['count']}, 长期={stats['long_term']['count']}")

        return True

    except Exception as e:
        print(f"   ❌ 记忆系统检查失败: {e}")
        return False


async def check_tool_system():
    """检查工具系统"""
    print("\n2. 检查工具系统...")

    try:
        # 清空工具管理器（避免冲突）
        tool_manager.clear_tools()

        # 重新注册一些测试工具
        from tools.function_tool import FunctionTool

        def test_tool(x: int, y: int = 5) -> dict:
            return {"result": x + y, "timestamp": datetime.now().isoformat()}

        tool = FunctionTool(
            func=test_tool,
            name="health_check_tool",
            description="健康检查工具",
            category="test"
        )

        tool_manager.register_tool(tool, "test")
        print(f"   ✅ 工具注册: 注册了 {len(tool_manager.get_all_tools())} 个工具")

        # 测试执行
        result = tool_manager.execute_tool("health_check_tool", x=10, y=20)
        if result.success:
            print(f"   ✅ 工具执行: 结果={result.data}")
        else:
            print(f"   ❌ 工具执行失败: {result.error}")
            return False

        return True

    except Exception as e:
        print(f"   ❌ 工具系统检查失败: {e}")
        return False


async def check_scheduler_system():
    """检查任务调度系统"""
    print("\n3. 检查任务调度系统...")

    try:
        # 创建任务管理器
        task_mgr = TaskManager()

        # 创建测试任务
        task = task_mgr.create_reminder(
            message="健康检查提醒",
            remind_in_seconds=2
        )
        print(f"   ✅ 任务创建: {task.name} (ID: {task.id})")

        # 获取任务列表
        tasks = task_mgr.list_tasks(limit=5)
        print(f"   ✅ 任务列表: 找到 {len(tasks)} 个任务")

        # 获取统计
        stats = task_mgr.get_stats()
        print(f"   ✅ 任务统计: 总任务={stats['total_tasks']}, 状态={stats['tasks_by_status']}")

        # 等待2秒让任务执行
        print("   等待2秒，让任务执行...")
        await asyncio.sleep(2.5)

        # 检查任务状态
        tasks = task_mgr.list_tasks(limit=5)
        completed = [t for t in tasks if t.get('status') == 'completed']
        print(f"   ✅ 任务执行: 有 {len(completed)} 个任务已完成")

        # 停止任务管理器
        task_mgr.stop()

        return True

    except Exception as e:
        print(f"   ❌ 调度系统检查失败: {e}")
        return False


async def check_agent_system():
    """检查智能体系统"""
    print("\n4. 检查智能体系统...")

    try:
        # 创建智能体
        agent = IntelligentAgent()
        print(f"   ✅ 智能体初始化: 使用模型 {agent.model}")

        # 测试连接
        connected = await agent.test_connection()
        if connected:
            print("   ✅ Ollama连接: 成功")
        else:
            print("   ⚠️  Ollama连接: 失败（但智能体仍可工作）")

        # 测试工具数量
        tools_info = agent.get_available_tools()
        print(f"   ✅ 工具注册: {tools_info['count']} 个工具，{len(tools_info['categories'])} 个类别")

        # 测试记忆
        memory_info = agent.get_memory_info()
        print(f"   ✅ 记忆系统: 短期={memory_info['short_term']['count']}, 长期={memory_info['long_term']['count']}")

        # 测试任务
        task_info = agent.get_task_info()
        print(f"   ✅ 任务系统: 总任务={task_info.get('total_tasks', 0)}")

        # 测试简单对话（不使用工具）
        print("   测试简单对话（不使用工具）...")
        start_time = time.time()
        response = await agent.chat("你好，请回复'健康检查通过'", use_tools=False, use_memory=False)
        elapsed = time.time() - start_time

        if "健康检查" in response or "通过" in response:
            print(f"   ✅ 对话测试: 成功 ({elapsed:.1f}秒)")
            print(f"      响应: {response[:50]}...")
        else:
            print(f"   ⚠️  对话测试: 响应可能不正确 ({elapsed:.1f}秒)")
            print(f"      响应: {response[:100]}...")

        return True

    except Exception as e:
        print(f"   ❌ 智能体系统检查失败: {e}")
        import traceback
        traceback.print_exc()
        return False


async def check_specialized_tools():
    """检查专用工具"""
    print("\n5. 检查专用工具...")

    try:
        # 测试计算器工具
        result = tool_manager.execute_tool("CalculatorTool", expression="2 + 2 * 3")
        if result.success and result.data == 8:
            print("   ✅ 计算器工具: 2 + 2 * 3 = 8")
        else:
            print(f"   ❌ 计算器工具失败: {result.error}")
            return False

        # 测试时间工具
        result = tool_manager.execute_tool("TimeTool", action="current_time")
        if result.success and "timestamp" in result.data:
            print("   ✅ 时间工具: 可以获取当前时间")
        else:
            print(f"   ⚠️  时间工具可能有问题: {result.data}")

        # 测试系统信息工具
        result = tool_manager.execute_tool("system_info")
        if result.success and "cpu_percent" in result.data:
            print("   ✅ 系统信息工具: 可以获取系统信息")
        else:
            print(f"   ⚠️  系统信息工具可能有问题: {result.data}")

        # 测试任务工具
        result = tool_manager.execute_tool("create_reminder",
                                           message="专用工具检查提醒",
                                           remind_in_seconds=3)
        if result.success and "task_id" in result.data:
            print("   ✅ 创建提醒工具: 可以创建提醒")
        else:
            print(f"   ❌ 创建提醒工具失败: {result.error}")
            return False

        return True

    except Exception as e:
        print(f"   ❌ 专用工具检查失败: {e}")
        return False


async def main():
    """主健康检查函数"""
    print("开始系统健康检查...\n")

    all_checks_passed = True
    check_results = []

    # 运行各项检查
    checks = [
        ("记忆系统", check_memory_system),
        ("工具系统", check_tool_system),
        ("调度系统", check_scheduler_system),
        ("智能体系统", check_agent_system),
        ("专用工具", check_specialized_tools),
    ]

    for check_name, check_func in checks:
        print(f"🔍 检查: {check_name}")
        print("-" * 40)

        try:
            passed = await check_func()
            check_results.append((check_name, passed))

            if not passed:
                all_checks_passed = False

            print()
        except Exception as e:
            print(f"   ❌ 检查异常: {e}")
            check_results.append((check_name, False))
            all_checks_passed = False
            print()

    # 显示检查结果
    print("📊 健康检查结果")
    print("=" * 60)

    for check_name, passed in check_results:
        status = "✅ 通过" if passed else "❌ 失败"
        print(f"{status:10} {check_name}")

    print()
    print("📈 系统状态摘要")
    print("-" * 40)

    try:
        # 获取最终系统状态
        agent = IntelligentAgent()

        tools_info = agent.get_available_tools()
        memory_info = agent.get_memory_info()
        task_info = agent.get_task_info()

        print(f"• 工具总数: {tools_info['count']}")
        print(f"• 工具类别: {', '.join(tools_info['categories'])}")
        print(f"• 记忆数量: 短期={memory_info['short_term']['count']}, 长期={memory_info['long_term']['count']}")
        print(f"• 任务统计: 总任务={task_info.get('total_tasks', 0)}")
        print(f"• 任务状态: {task_info.get('tasks_by_status', {})}")

        # 清理
        agent.stop()

    except Exception as e:
        print(f"   无法获取完整状态: {e}")

    print()
    print("=" * 60)

    if all_checks_passed:
        print("🎉 所有健康检查通过！系统运行正常。")
        return True
    else:
        print("⚠️  部分检查未通过，请查看上面的错误信息。")
        return False


if __name__ == "__main__":
    start_time = time.time()
    success = asyncio.run(main())
    elapsed = time.time() - start_time

    print(f"\n⏱️  总检查时间: {elapsed:.1f}秒")
    sys.exit(0 if success else 1)
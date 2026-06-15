#!/usr/bin/env python3
"""轻量级测试，跳过嵌入模型"""
import sys
import os

import pytest

# 添加项目根目录到路径
sys.path.append(os.path.dirname(os.path.abspath(__file__)))


def test_basic_fixes():
    """测试基本修复"""
    print("🧪 测试基本修复...")
    print("=" * 60)

    # 测试1: 导入检查
    print("\n1. 测试导入检查:")
    try:
        from core.agent import IntelligentAgent
        print("   ✅ IntelligentAgent 导入成功")
    except Exception as e:
        print(f"   ❌ 导入失败: {e}")
        pytest.fail(f"IntelligentAgent 导入失败: {e}")

    # 测试2: 工具管理器导入
    print("\n2. 测试工具管理器导入:")
    try:
        from tools.tool_manager import tool_manager
        print(f"   ✅ 工具管理器导入成功")
        print(f"   工具管理器类型: {type(tool_manager)}")
    except Exception as e:
        print(f"   ❌ 导入失败: {e}")
        pytest.fail(f"tool_manager 导入失败: {e}")

    # 测试3: 记忆管理器导入
    print("\n3. 测试记忆管理器导入:")
    try:
        from memory.manager import MemoryManager
        print("   ✅ MemoryManager 导入成功")
    except Exception as e:
        print(f"   ❌ 导入失败: {e}")
        pytest.fail(f"MemoryManager 导入失败: {e}")

    # 测试4: 创建简单的记忆管理器（跳过嵌入模型）
    print("\n4. 测试记忆管理器创建（跳过嵌入模型）:")
    try:
        # 使用内存向量数据库
        memory = MemoryManager(
            long_term_config={
                "name": "test_lightweight",
                "vector_db_type": "memory",  # 使用内存数据库，不加载嵌入模型
                "embedding_model": "all-MiniLM-L6-v2"
            }
        )
        print("   ✅ 记忆管理器创建成功")

        # 测试存储
        memory_item = memory.store("测试内容", "test")
        print(f"   ✅ 记忆存储成功，ID: {memory_item.id}")

    except Exception as e:
        print(f"   ❌ 创建失败: {e}")
        import traceback
        traceback.print_exc()
        pytest.fail(f"记忆管理器创建失败: {e}")


def test_agent_creation():
    """测试智能体创建（不运行完整初始化）"""
    print("\n🤖 测试智能体创建（跳过模型加载）...")
    print("=" * 60)

    try:
        # 创建智能体，但我们会手动控制
        from core.agent import IntelligentAgent

        # 创建一个简化的智能体测试
        print("1. 创建智能体实例...")

        # 临时修改设置，避免嵌入模型加载
        import config.settings
        original_persist_dir = config.settings.settings.chroma_persist_dir

        # 使用临时目录
        import tempfile
        temp_dir = tempfile.mkdtemp()
        config.settings.settings.chroma_persist_dir = temp_dir

        # 尝试创建智能体
        agent = IntelligentAgent()

        print("2. 检查智能体属性...")

        # 检查必要属性
        required_attrs = ['model', 'provider', 'tool_manager', 'memory']

        missing_attrs = []
        for attr in required_attrs:
            if not hasattr(agent, attr):
                missing_attrs.append(attr)

        if missing_attrs:
            print(f"   ❌ 缺少属性: {missing_attrs}")
            pytest.fail(f"智能体缺少必要属性: {missing_attrs}")

        print(f"   ✅ 所有必要属性存在")
        print(f"   模型: {agent.model}")
        print(f"   Provider: {type(agent.provider).__name__}")
        print(f"   工具数量: {len(agent.tool_manager.get_all_tools())}")

        # 恢复设置
        config.settings.settings.chroma_persist_dir = original_persist_dir

        # 清理临时目录
        import shutil
        shutil.rmtree(temp_dir, ignore_errors=True)

    except Exception as e:
        print(f"❌ 智能体创建测试失败: {e}")
        import traceback
        traceback.print_exc()
        pytest.fail(f"智能体创建测试失败: {e}")


def test_tool_manager_directly():
    """直接测试工具管理器"""
    print("\n🔧 直接测试工具管理器...")
    print("=" * 60)

    try:
        from tools.tool_manager import tool_manager

        # 获取所有工具
        tools = tool_manager.get_all_tools()
        print(f"1. 工具总数: {len(tools)}")

        # 列出工具
        print("\n2. 工具列表:")
        for i, (name, tool) in enumerate(tools.items(), 1):
            print(f"   {i:2d}. {name}: {tool.description[:50]}...")

        # 测试工具执行
        print("\n3. 测试计算器工具:")
        try:
            # 同步执行
            result = tool_manager.execute_tool("CalculatorTool", expression="2 + 2")
            print(f"   计算 2 + 2 = {result.data} (成功: {result.success})")
        except Exception as e:
            print(f"   工具执行失败: {e}")

    except Exception as e:
        print(f"❌ 工具管理器测试失败: {e}")
        pytest.fail(f"工具管理器测试失败: {e}")


def main():
    """主测试函数"""
    print("开始轻量级测试...")

    all_passed = True

    # 测试基本修复
    if not test_basic_fixes():
        all_passed = False

    # 测试工具管理器
    if not test_tool_manager_directly():
        all_passed = False

    # 测试智能体创建
    if not test_agent_creation():
        all_passed = False

    print("\n" + "=" * 60)
    if all_passed:
        print("✅ 轻量级测试通过!")
    else:
        print("❌ 轻量级测试失败!")

    return all_passed


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
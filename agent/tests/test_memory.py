#!/usr/bin/env python3
"""测试记忆系统"""
import asyncio
import sys
import os
import tempfile
import shutil

import pytest

# 添加项目根目录到路径
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from memory.manager import MemoryManager


async def test_memory_storage():
    """测试记忆存储"""
    print("🧠 测试记忆存储...")
    print("=" * 60)

    # 创建临时目录
    temp_dir = tempfile.mkdtemp()
    print(f"临时目录: {temp_dir}")

    try:
        # 初始化记忆管理器
        memory = MemoryManager(
            long_term_config={
                "name": "test_memory",
                "vector_db_type": "memory",  # 使用内存数据库测试
                "embedding_model": "all-MiniLM-L6-v2",
                "persist_dir": temp_dir
            }
        )

        # 测试存储对话
        print("\n1. 测试存储对话:")
        conv1 = memory.store_conversation("user", "你好，今天天气怎么样？")
        conv2 = memory.store_conversation("assistant", "今天天气晴朗，温度25度。")

        print(f"  存储对话1: {conv1.content[:30]}...")
        print(f"  存储对话2: {conv2.content[:30]}...")

        # 测试存储知识
        print("\n2. 测试存储知识:")
        knowledge1 = memory.store_knowledge(
            "Python是一种高级编程语言，由Guido van Rossum创建。",
            importance=0.8
        )
        knowledge2 = memory.store_knowledge(
            "人工智能是计算机科学的一个分支，致力于创建智能机器。",
            importance=0.9
        )

        print(f"  存储知识1: {knowledge1.content[:30]}...")
        print(f"  存储知识2: {knowledge2.content[:30]}...")

        # 测试存储事实
        print("\n3. 测试存储事实:")
        fact1 = memory.store_fact("地球绕太阳公转一周需要365.25天。")
        print(f"  存储事实: {fact1.content}")

        # 测试获取最近的对话
        print("\n4. 测试获取最近的对话:")
        recent = memory.get_recent_conversations(3)
        print(f"  最近 {len(recent)} 个对话:")
        for i, conv in enumerate(recent, 1):
            print(f"    {i}. {conv.content[:50]}...")

        # 测试搜索记忆
        print("\n5. 测试搜索记忆:")
        search_results = memory.search_relevant_memories("Python 语言", 3)
        print(f"  搜索 'Python 语言' 的结果:")
        for i, result in enumerate(search_results, 1):
            print(f"    {i}. 相似度: {result.similarity:.3f}")
            print(f"       内容: {result.memory.content[:60]}...")

        # 测试获取重要记忆
        print("\n6. 测试获取重要记忆:")
        important = memory.get_important_memories(0.7, 3)
        print(f"  重要记忆 ({len(important)} 个):")
        for i, mem in enumerate(important, 1):
            print(f"    {i}. 重要性: {mem.importance:.2f}")
            print(f"       内容: {mem.content[:60]}...")

        # 测试统计信息
        print("\n7. 测试统计信息:")
        stats = memory.get_stats()
        print(f"  短期记忆数量: {stats['short_term']['count']}")
        print(f"  长期记忆数量: {stats['long_term']['count']}")

        # 测试构建上下文
        print("\n8. 测试构建上下文:")
        context = memory.get_context_for_query("编程语言", 2, 2)
        print(f"  上下文长度: {len(context)} 字符")
        print(f"  上下文预览: {context[:200]}...")

        return True

    finally:
        # 清理临时目录
        shutil.rmtree(temp_dir, ignore_errors=True)
        print(f"\n✅ 清理临时目录")


async def test_memory_retrieval():
    """测试记忆检索"""
    print("\n" + "=" * 60)
    print("🔍 测试记忆检索...")
    print("=" * 60)

    memory = MemoryManager(
        long_term_config={
            "name": "test_retrieval",
            "vector_db_type": "memory",
            "embedding_model": "all-MiniLM-L6-v2"
        }
    )

    # 存储测试数据
    test_data = [
        ("Python是一种流行的编程语言", "knowledge", 0.8),
        ("Java也是一种编程语言", "knowledge", 0.7),
        ("今天天气很好", "conversation", 0.3),
        ("机器学习是AI的一个子领域", "knowledge", 0.9),
        ("深度学习使用神经网络", "knowledge", 0.85),
    ]

    for content, category, importance in test_data:
        if category == "conversation":
            memory.store_conversation("user", content)
        else:
            memory.store(content, category, importance=importance)

    # 测试不同查询
    test_queries = [
        ("编程语言", 3),
        ("机器学习", 2),
        ("天气", 2),
    ]

    for query, limit in test_queries:
        print(f"\n查询: '{query}' (限制: {limit}):")
        results = memory.retrieve(query, memory_type="both", limit=limit)

        for i, result in enumerate(results, 1):
            print(f"  {i}. 相似度: {result.similarity:.3f}, 分数: {result.score:.3f}")
            print(f"     类别: {result.memory.metadata.get('category', 'unknown')}")
            print(f"     内容: {result.memory.content[:60]}...")

    return True


async def test_memory_persistence():
    """测试记忆持久化"""
    print("\n" + "=" * 60)
    print("💾 测试记忆持久化...")
    print("=" * 60)

    import tempfile
    import os

    # 创建临时目录
    temp_dir = tempfile.mkdtemp()
    backup_file = os.path.join(temp_dir, "memory_backup.json")

    try:
        # 创建记忆管理器并存储数据
        memory1 = MemoryManager(
            long_term_config={
                "name": "test_persistence",
                "vector_db_type": "memory",
                "embedding_model": "all-MiniLM-L6-v2"
            }
        )

        # 存储一些记忆
        memory1.store("测试记忆1", "test", importance=0.8)
        memory1.store("测试记忆2", "test", importance=0.7)
        memory1.store_conversation("user", "测试对话")

        # 保存备份
        memory1.save_to_file(backup_file)
        print(f"记忆已保存到: {backup_file}")

        # 创建新的记忆管理器
        memory2 = MemoryManager(
            long_term_config={
                "name": "test_persistence2",
                "vector_db_type": "memory",
                "embedding_model": "all-MiniLM-L6-v2"
            }
        )

        # 加载备份
        memory2.load_from_file(backup_file)
        print(f"记忆已从 {backup_file} 加载")

        # 验证加载的记忆
        stats = memory2.get_stats()
        print(f"加载后记忆数量:")
        print(f"  短期记忆: {stats['short_term']['count']}")
        print(f"  长期记忆: {stats['long_term']['count']}")

        return True

    finally:
        # 清理
        if os.path.exists(backup_file):
            os.remove(backup_file)
        if os.path.exists(temp_dir):
            os.rmdir(temp_dir)


async def test_agent_with_memory():
    """测试带记忆的智能体"""
    print("\n" + "=" * 60)
    print("🤖 测试带记忆的智能体...")
    print("=" * 60)

    from core.agent import IntelligentAgent

    # 创建智能体
    agent = IntelligentAgent()

    # 集成测试：需要可用的本地 Ollama，云端模式或无连接时 skip
    from services.ollama_provider import OllamaProvider
    if not isinstance(agent.provider, OllamaProvider) or not agent.provider.check_connection():
        pytest.skip("integration test: requires local Ollama")

    # 获取记忆信息
    memory_info = agent.get_memory_info()
    print(f"记忆统计: {memory_info}")

    # 测试对话（带记忆）
    test_messages = [
        "你好，我的名字是张三",
        "记住：我喜欢吃苹果",
        "我之前告诉你我喜欢吃什么？",
        "Python是什么？",
        "记住：Python是一种编程语言",
        "什么是Python？"
    ]

    for i, message in enumerate(test_messages, 1):
        print(f"\n{i}. 用户: {message}")

        response = await agent.chat(message, use_memory=True, use_tools=True)
        content = response.get("content", str(response)) if isinstance(response, dict) else str(response)
        print(f"   助手: {content[:150]}...")

        # 稍微等待
        await asyncio.sleep(1)

    # 显示记忆统计
    final_stats = agent.get_memory_info()
    print(f"\n最终记忆统计:")
    print(f"  短期记忆: {final_stats['short_term']['count']}")
    print(f"  长期记忆: {final_stats['long_term']['count']}")

    return True


async def main():
    """主测试函数"""
    print("开始测试记忆系统...")

    all_passed = True

    # 测试记忆存储
    if not await test_memory_storage():
        all_passed = False

    # 测试记忆检索
    if not await test_memory_retrieval():
        all_passed = False

    # 测试记忆持久化
    if not await test_memory_persistence():
        all_passed = False

    # 测试带记忆的智能体
    if not await test_agent_with_memory():
        all_passed = False

    print("\n" + "=" * 60)
    if all_passed:
        print("✅ 所有记忆测试通过!")
    else:
        print("❌ 部分测试失败!")

    return all_passed


if __name__ == "__main__":
    success = asyncio.run(main())
    sys.exit(0 if success else 1)
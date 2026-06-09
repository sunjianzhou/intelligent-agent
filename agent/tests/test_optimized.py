#!/usr/bin/env python3
"""测试优化后的系统"""
import sys
import os
import asyncio
import time

# 添加项目根目录到路径
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from core.agent import IntelligentAgent


async def test_optimized_startup():
    """测试优化后的启动速度"""
    print("⚡ 测试优化后的启动速度...")
    print("=" * 60)

    start_time = time.time()

    try:
        # 第一次创建智能体
        print("1. 第一次创建智能体（会加载嵌入模型）...")
        agent1 = IntelligentAgent()
        first_load_time = time.time() - start_time
        print(f"   第一次启动耗时: {first_load_time:.2f}秒")

        # 测试连接
        print("\n2. 测试Ollama连接...")
        connected = await agent1.test_connection()
        if connected:
            print("   ✅ Ollama连接成功")
        else:
            print("   ⚠️  Ollama连接失败")

        # 第二次创建智能体（应该使用缓存）
        print("\n3. 第二次创建智能体（应该使用缓存）...")
        start_time2 = time.time()
        agent2 = IntelligentAgent()
        second_load_time = time.time() - start_time2
        print(f"   第二次启动耗时: {second_load_time:.2f}秒")

        # 比较启动时间
        if second_load_time < first_load_time * 0.5:  # 第二次应该快很多
            print(f"   ✅ 启动速度提升: {(first_load_time - second_load_time) / first_load_time * 100:.0f}%")
        else:
            print(f"   ⚠️  启动速度提升不明显")

        # 测试简单对话
        print("\n4. 测试简单对话...")
        response = await agent1.chat("你好，测试优化", use_tools=False, use_memory=False)
        print(f"   响应: {response[:100]}...")

        return True

    except Exception as e:
        print(f"❌ 测试失败: {e}")
        import traceback
        traceback.print_exc()
        return False


async def test_memory_performance():
    """测试记忆性能"""
    print("\n🧠 测试记忆性能...")
    print("=" * 60)

    try:
        from memory.manager import MemoryManager

        # 创建记忆管理器
        start_time = time.time()
        memory = MemoryManager(
            long_term_config={
                "name": "test_performance",
                "vector_db_type": "memory",  # 使用内存数据库
                "embedding_model": "all-MiniLM-L6-v2"
            }
        )
        init_time = time.time() - start_time
        print(f"1. 记忆管理器初始化耗时: {init_time:.2f}秒")

        # 测试存储性能
        print("\n2. 测试记忆存储性能:")
        store_times = []
        for i in range(5):
            content = f"测试记忆内容 {i + 1}: 这是一条测试记忆，用于性能测试。"
            start_time = time.time()
            memory.store(content, "test", importance=0.5)
            store_time = time.time() - start_time
            store_times.append(store_time)
            print(f"   存储 {i + 1}: {store_time:.3f}秒")

        avg_store_time = sum(store_times) / len(store_times)
        print(f"   平均存储时间: {avg_store_time:.3f}秒")

        # 测试检索性能
        print("\n3. 测试记忆检索性能:")
        start_time = time.time()
        results = memory.search_relevant_memories("测试", limit=5)
        search_time = time.time() - start_time
        print(f"   检索 {len(results)} 条结果耗时: {search_time:.3f}秒")

        return True

    except Exception as e:
        print(f"❌ 性能测试失败: {e}")
        return False


async def main():
    """主测试函数"""
    print("开始优化测试...")

    all_passed = True

    # 测试优化后的启动速度
    if not await test_optimized_startup():
        all_passed = False

    # 测试记忆性能
    if not await test_memory_performance():
        all_passed = False

    print("\n" + "=" * 60)
    if all_passed:
        print("✅ 优化测试通过!")
    else:
        print("❌ 优化测试失败!")

    return all_passed


if __name__ == "__main__":
    success = asyncio.run(main())
    sys.exit(0 if success else 1)
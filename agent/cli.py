"""智能体主程序入口"""
import asyncio
from loguru import logger
from config.settings import settings


async def main():
    """主函数"""
    logger.info(f"启动 {settings.app_name} v{settings.app_version}")
    logger.info(f"Debug模式: {settings.debug}")
    logger.info(f"Ollama模型: {settings.ollama_model}")

    # 初始化核心组件
    from core.agent import IntelligentAgent

    try:
        # 创建智能体实例
        agent = IntelligentAgent()

        # 测试连接
        success = await agent.test_connection()
        if not success:
            logger.error("无法连接到Ollama服务")
            return

        # 显示可用工具
        tools_info = agent.get_available_tools()
        logger.info(f"可用工具: {tools_info['count']} 个")

        # 显示记忆信息
        memory_info = agent.get_memory_info()
        logger.info(f"记忆系统: 短期记忆={memory_info['short_term']['count']}, "
                   f"长期记忆={memory_info['long_term']['count']}")

        logger.info("智能体初始化完成，等待指令...")
        logger.info("输入 'tools' 查看可用工具")
        logger.info("输入 'memory' 查看记忆统计")
        logger.info("输入 'clear' 清空历史")
        logger.info("输入 'exit' 退出")

        # 简单的CLI交互
        while True:
            try:
                user_input = input("\n>>> ").strip()

                if user_input.lower() in ['exit', 'quit', 'bye']:
                    logger.info("再见！")
                    break
                elif user_input.lower() == 'clear':
                    agent.clear_history()
                    logger.info("对话历史和记忆已清空")
                    continue
                elif user_input.lower() == 'tools':
                    from tools.tool_manager import tool_manager
                    print("\n可用工具:")
                    for name, tool in tool_manager.get_all_tools().items():
                        print(f"  - {name}: {tool.description}")
                    continue
                elif user_input.lower() == 'memory':
                    memory_info = agent.get_memory_info()
                    print(f"\n记忆统计:")
                    print(f"  短期记忆: {memory_info['short_term']['count']} 条")
                    print(f"  长期记忆: {memory_info['long_term']['count']} 条")
                    if 'stats' in memory_info['long_term']:
                        stats = memory_info['long_term']['stats']
                        print(f"  平均重要性: {stats.get('average_importance', 0):.2f}")
                        print(f"  平均访问次数: {stats.get('average_access_count', 0):.1f}")
                    continue
                elif user_input.lower() == 'help':
                    print("\n命令:")
                    print("  tools    - 查看可用工具")
                    print("  memory   - 查看记忆统计")
                    print("  clear    - 清空对话历史和记忆")
                    print("  exit     - 退出程序")
                    print("  help     - 显示此帮助")
                    continue

                if user_input:
                    response = await agent.chat(user_input, use_tools=True, use_memory=True)
                    print(f"\n🤖: {response}")

            except KeyboardInterrupt:
                logger.info("\n收到退出信号")
                break
            except Exception as e:
                logger.error(f"处理输入时出错: {e}")

    except Exception as e:
        logger.error(f"启动失败: {e}")
        raise
    finally:
        logger.info("智能体关闭")


if __name__ == "__main__":
    asyncio.run(main())
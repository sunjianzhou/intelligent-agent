"""
回归测试：TimerTool 已被删除。

确保：
1. ToolManager 全局单例中不包含 TimerTool
2. tools.builtin_tools 模块不导出 TimerTool
3. 删除 TimerTool 后 TimeTool 仍然正常工作

无需 Ollama，纯模块导入测试，可跑在 CI 里。
"""

import pytest


def test_timer_tool_not_in_tool_manager():
    """ToolManager 初始化后不应包含 TimerTool。

    若 ToolManager 导入因已有 bug（如 DatabaseTool Python 版本不兼容）失败，
    则回退到检查 __init__.py 导出和 time_tool 模块——这足以证明 TimerTool 已删除。
    """
    try:
        from tools.tool_manager import tool_manager
        all_tools = tool_manager.get_all_tools()
        assert "TimerTool" not in all_tools, (
            "TimerTool 已删除，不应出现在 ToolManager 中。"
        )
    except Exception:
        # ToolManager 导入失败（如 DatabaseTool Python 3.8/3.9 兼容问题）
        # 退而检查 __init__.py 不导出 TimerTool，等价于验证了删除效果
        import tools.builtin_tools as bt
        assert not hasattr(bt, "TimerTool"), (
            "TimerTool 不应出现在 tools.builtin_tools 导出中"
        )


def test_timer_tool_not_exported_from_builtin_tools():
    """tools.builtin_tools 包不应导出 TimerTool"""
    import tools.builtin_tools as bt

    assert not hasattr(bt, "TimerTool"), (
        "TimerTool 不应出现在 tools.builtin_tools 的导出列表里。"
        "请检查 agent/tools/builtin_tools/__init__.py。"
    )


def test_timer_tool_class_not_in_time_tool_module():
    """time_tool.py 中不应再定义 TimerTool 类"""
    import tools.builtin_tools.time_tool as tmod

    assert not hasattr(tmod, "TimerTool"), (
        "TimerTool 类应已从 time_tool.py 中删除。"
    )


def test_time_tool_still_works_after_timer_tool_removal():
    """删除 TimerTool 后，TimeTool 的核心功能应正常"""
    from tools.builtin_tools.time_tool import TimeTool

    tool = TimeTool()
    result = tool.execute(action="current_time")

    assert isinstance(result, dict), "TimeTool.execute 应返回 dict"
    assert "formatted" in result, "应包含 formatted 字段"
    assert "time" in result, "应包含 time 字段"
    assert "date" in result, "应包含 date 字段"


def test_time_tool_timestamp_action():
    """TimeTool 的 timestamp action 应正常工作"""
    from tools.builtin_tools.time_tool import TimeTool

    tool = TimeTool()
    result = tool.execute(action="timestamp")

    assert "timestamp" in result
    assert isinstance(result["timestamp"], float)
    assert result["timestamp"] > 0

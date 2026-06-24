"""测试 FileTool 对 soul/MEMORY.md 的窄白名单授权（TODO-83）：
允许该文件在 safe_directories 之外读写，但禁止 delete/move，
且不会放宽到 soul/ 下的其他文件。"""
import pytest

from tools.builtin_tools import file_tool as file_tool_module
from tools.builtin_tools.file_tool import FileTool


@pytest.fixture
def whitelisted_tool(tmp_path, monkeypatch):
    """构造一个白名单文件指向 tmp_path 的 FileTool 实例，避免触碰真实 soul/MEMORY.md。
    显式清空 safe_directories，模拟该文件本就在常规安全目录之外，
    这样测试才能证明"是白名单生效"而不是"碰巧落在 home/cwd 里"。
    """
    target = tmp_path / "MEMORY.md"
    target.write_text("# 占位\n", encoding="utf-8")
    monkeypatch.setattr(file_tool_module, "MEMORY_MD_PATH", str(target))
    tool = FileTool()
    tool.safe_directories = []
    return tool, str(target)


def test_whitelisted_file_writable_outside_safe_directories(whitelisted_tool):
    tool, target = whitelisted_tool
    result = tool.execute(action="write", path=target, content="新内容")
    assert result["action"] == "write"
    assert open(target, encoding="utf-8").read() == "新内容"


def test_whitelisted_file_delete_blocked(whitelisted_tool):
    tool, target = whitelisted_tool
    with pytest.raises(PermissionError, match="禁止 delete"):
        tool.execute(action="delete", path=target)


def test_whitelisted_file_move_blocked(whitelisted_tool):
    tool, target = whitelisted_tool
    with pytest.raises(PermissionError, match="禁止 move"):
        tool.execute(action="move", path=target, dst=target + ".moved")


def test_sibling_file_not_whitelisted(whitelisted_tool, tmp_path):
    """白名单只精确匹配一个文件路径，同目录下的其他文件不受影响。"""
    tool, _ = whitelisted_tool
    sibling = tmp_path / "SOUL.md"
    with pytest.raises(PermissionError, match="不在安全目录内"):
        tool.execute(action="write", path=str(sibling), content="hacked")

# 自维护版本化记忆技能（TODO-83）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让心跳巡检在非安静时段顺带触发一次节流的记忆归并：LLM 自主判断近期是否有值得永久记录的内容，仅获得对 `soul/MEMORY.md` 这一个文件的读写权限（通过代码层硬限制，不依赖关键词软匹配），写入前自动备份、写入用原子替换，节流时间戳由代码（非 LLM）维护。

**Architecture:** 三处改动协同：① `FileTool` 新增单文件白名单，允许 `soul/MEMORY.md` 在常规 `safe_directories` 之外读写，但禁止对它执行 delete/move；② `chat()` / `_call_model_with_tools` 新增 `allowed_tool_categories` 参数，在意图过滤和 skill 应用之后做最终的硬性工具分类交集过滤，防止任何上游逻辑把工具集合悄悄放宽；③ `SimpleTaskScheduler.heartbeat_check_action` 在安静时段判断之后调用新方法 `_consolidate_memory`，该方法独立完成节流判断、备份、调用受限 LLM、原子写回时间戳。

**Tech Stack:** Python 3.10，pytest + pytest-asyncio，复用项目现有的 `unittest.mock.AsyncMock/MagicMock` 测试约定。

## Global Constraints

- 节流阈值默认 24 小时，通过模块常量 `_MEMORY_CONSOLIDATE_THROTTLE_HOURS` 定义，不做用户级配置项（YAGNI，超出范围再加）。
- 不新增 `agent/prompts/` 目录或模板加载机制；prompt 沿用本文件现有的模块级字符串常量写法（参照 `_HEARTBEAT_DECISION_PROMPT`）。
- 不实现事件计数双触发、checksum 校验、黑名单文件清单——均为本次评审中判定为 YAGNI / 无消费者的项，明确不做。
- 所有新增/修改代码遵循仓库现有命名与日志风格（`logger.info/warning/error`，前缀 `[memory_consolidate]`）。
- 时间戳格式沿用仓库既有约定：`datetime.now().isoformat()`（naive，本地时间），与 `heartbeat_check`/`llm_generate` 一致，不引入 RFC3339 时区感知。

---

### Task 1: FileTool 单文件白名单（禁删/禁移）

**Files:**
- Modify: `agent/tools/builtin_tools/file_tool.py`
- Test: `agent/tests/test_file_tool_whitelist.py`（新建）

**Interfaces:**
- Produces: 模块级常量 `MEMORY_MD_PATH: str`（绝对路径字符串，由 `Path(__file__)` 锚定，等价于 `<项目根>/soul/MEMORY.md`），供 Task 3 在文档/注释中引用，也供测试 monkeypatch。
- Produces: `FileTool._check_path_safety(self, path: str, action: str = "") -> None`（原方法签名新增 `action` 参数，默认值保证向后兼容）。
- Produces: `FileTool._extra_writable_files: List[str]` 实例属性。

- [ ] **Step 1: 写失败测试**

创建 `agent/tests/test_file_tool_whitelist.py`：

```python
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
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd agent && conda run -n python310 python -m pytest tests/test_file_tool_whitelist.py -v
```
Expected: 4 个测试全部 FAIL（`MEMORY_MD_PATH` 不存在 / `_check_path_safety` 不接受 `action` 参数 / 白名单逻辑不存在）。

- [ ] **Step 3: 实现**

在 `agent/tools/builtin_tools/file_tool.py` 顶部 import 之后新增模块级常量：

```python
# soul/MEMORY.md 绝对路径，由文件位置锚定（不依赖 CWD），供 TODO-83 记忆归并使用
_PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent
MEMORY_MD_PATH = os.path.abspath(str(_PROJECT_ROOT / "soul" / "MEMORY.md"))
```

`__init__` 内追加（紧跟 `self.safe_directories = ...` 之后）：

```python
        # 额外授权的单文件白名单（TODO-83）：允许 soul/MEMORY.md 在 safe_directories
        # 之外仍可读写，但 _check_path_safety 会禁止对它执行 delete/move，
        # 防止心跳记忆归并的自治 LLM 调用误删/误移走唯一副本。
        self._extra_writable_files = [MEMORY_MD_PATH]
```

`execute()` 方法内，原来的 `self._check_path_safety(path)` 改为：

```python
        # 安全检查
        self._check_path_safety(path, action)
```

`_check_path_safety` 整体替换为：

```python
    def _check_path_safety(self, path: str, action: str = "") -> None:
        """检查路径安全性。

        白名单文件（如 soul/MEMORY.md）允许超出 safe_directories 范围读写，
        但禁止 delete/move，防止自治记忆归并把唯一副本删掉或移走。
        """
        abs_path = os.path.abspath(path)

        if abs_path in self._extra_writable_files:
            if action in ("delete", "move"):
                raise PermissionError(f"白名单文件禁止 {action} 操作: {path}")
            return

        # 检查是否在安全目录内
        is_safe = any(abs_path.startswith(safe_dir) for safe_dir in self.safe_directories)

        if not is_safe:
            raise PermissionError(f"路径不在安全目录内: {path}")
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd agent && conda run -n python310 python -m pytest tests/test_file_tool_whitelist.py -v
```
Expected: 4 passed

- [ ] **Step 5: 跑一遍既有 FileTool 测试确认无回归**

```bash
cd agent && conda run -n python310 python -m pytest tests/test_tools.py -v
```
Expected: 既有测试全部 PASS（白名单不影响 home/cwd 下的常规读写路径）。

- [ ] **Step 6: Commit**

```bash
git add agent/tools/builtin_tools/file_tool.py agent/tests/test_file_tool_whitelist.py
git commit -m "feat(tools): FileTool whitelist soul/MEMORY.md for write, block delete/move"
```

---

### Task 2: chat() 工具分类硬限制（`allowed_tool_categories`）

**Files:**
- Modify: `agent/core/conversation_flow.py:277-378`（`chat()` 方法签名 + ReAct 循环调用处）
- Modify: `agent/core/tool_dispatcher.py:866-907`（`_call_model_with_tools`）
- Test: `agent/tests/test_agent_core.py`（新增测试函数，文件已存在）

**Interfaces:**
- Consumes: 无新依赖。
- Produces: `chat(..., allowed_tool_categories: Optional[List[str]] = None)` — 当非 None 时，无论意图关键词/embedding/skill 匹配出什么工具集合，最终只保留属于这些分类的工具，传给模型的 `tools` 参数严格收窄。
- Produces: `_call_model_with_tools(..., allowed_tool_categories: Optional[List[str]] = None)` 同名参数语义。

- [ ] **Step 1: 写失败测试**

打开 `agent/tests/test_agent_core.py`，在文件末尾新增：

```python
@pytest.mark.asyncio
async def test_allowed_tool_categories_restricts_filtered_tools(monkeypatch):
    """allowed_tool_categories 必须是代码层硬限制：即使意图过滤/skill 应用给出了
    更宽的工具集合，最终传给模型的 filtered_tools 也只能保留指定分类下的工具。"""
    from core.agent import IntelligentAgent

    agent = IntelligentAgent.__new__(IntelligentAgent)
    agent.tool_manager = MagicMock()
    agent.tool_manager.get_all_tools.return_value = {
        "FileTool": object(), "ShellTool": object(), "FeishuIMTool": object(),
    }
    agent.tool_manager.get_tools_by_category.side_effect = lambda cat: (
        ["FileTool"] if cat == "file" else []
    )
    agent.skill_applicator = MagicMock()
    agent.skill_applicator.apply = AsyncMock(
        side_effect=lambda intent, messages, tools, call: (messages, tools, None)
    )
    agent._get_eff_provider = MagicMock(return_value=("ollama", "llama3"))
    agent._TEXT_TOOL_CALLING_PATTERNS = []
    agent._call_model_native_tools = AsyncMock(return_value={"content": "ok", "tool_calls": []})

    await agent._call_model_with_tools(
        [{"role": "user", "content": "随便聊聊"}],
        allowed_tool_categories=["file"],
    )

    passed_tools = agent._call_model_native_tools.call_args[0][1]
    assert list(passed_tools.keys()) == ["FileTool"]
```

确认该测试文件顶部已有 `from unittest.mock import MagicMock, AsyncMock` 和 `import pytest`（若没有，补上对应 import）。

- [ ] **Step 2: 运行测试确认失败**

```bash
cd agent && conda run -n python310 python -m pytest tests/test_agent_core.py::test_allowed_tool_categories_restricts_filtered_tools -v
```
Expected: FAIL，`TypeError: _call_model_with_tools() got an unexpected keyword argument 'allowed_tool_categories'`

- [ ] **Step 3: 实现 — tool_dispatcher.py**

`agent/core/tool_dispatcher.py` 中 `_call_model_with_tools` 签名与过滤逻辑改为：

```python
    async def _call_model_with_tools(
            self,
            messages: List[Dict[str, str]],
            config=None,
            intent_message: str = "",
            _trace_id: str = "",
            allowed_tool_categories: Optional[List[str]] = None,
    ) -> Dict[str, Any]:
        """调度入口：完成公共准备后派发到 text-tool 或 native FC 分支。

        allowed_tool_categories: 非 None 时做最终硬性收窄（代码层强制，不依赖关键词/
        embedding 软匹配，应用顺序在 intent 过滤和 skill 应用之后，保证不会被
        skill_applicator 重新加回被排除的工具）。仅供内部受限场景使用
        （如心跳记忆归并只允许 file 分类）。
        """
        _t0 = time.time()
        eff_provider, eff_model = self._get_eff_provider()

        filtered_tools = (
            self._filter_tools_by_intent(intent_message)
            if intent_message
            else self.tool_manager.get_all_tools()
        )
        if intent_message:
            messages, filtered_tools, _ = await self.skill_applicator.apply(
                intent_message, messages, filtered_tools, self._call_model
            )

        if allowed_tool_categories is not None:
            allowed_names: Set[str] = set()
            for cat in allowed_tool_categories:
                allowed_names.update(self.tool_manager.get_tools_by_category(cat))
            filtered_tools = {k: v for k, v in filtered_tools.items() if k in allowed_names}

        model_lower = (eff_model or "").lower()
```

（`Set` 已在文件顶部 `from typing import Dict, Any, Optional, List, Tuple, Set` 中导入，无需新增 import。）

- [ ] **Step 4: 实现 — conversation_flow.py**

`chat()` 方法签名追加参数（紧跟 `scene_mentioned: bool = False` 之后）：

```python
                   scene_mentioned: bool = False,
                   allowed_tool_categories: Optional[List[str]] = None) -> dict:
```

docstring 末尾追加一行：

```python
        allowed_tool_categories: 非 None 时硬限制本次对话可用的工具分类（代码层强制），
                          用于受限的内部自动化场景（如记忆归并只允许 file 分类）。
        """
```

ReAct 循环里的调用处（`for i in range(max_iterations):` 内）：

```python
            result = await self._call_model_with_tools(
                messages,
                intent_message=message if i == 0 else "",
                _trace_id=f"{_trace_id}:{i}",
                allowed_tool_categories=allowed_tool_categories,
            )
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd agent && conda run -n python310 python -m pytest tests/test_agent_core.py::test_allowed_tool_categories_restricts_filtered_tools -v
```
Expected: PASS

- [ ] **Step 6: 跑全量 core 测试确认无回归**

```bash
cd agent && conda run -n python310 python -m pytest tests/test_agent_core.py tests/test_tool_dispatcher.py -v
```
（若不存在 `test_tool_dispatcher.py`，跳过该文件，仅跑 `test_agent_core.py`。）
Expected: 全部 PASS

- [ ] **Step 7: Commit**

```bash
git add agent/core/conversation_flow.py agent/core/tool_dispatcher.py agent/tests/test_agent_core.py
git commit -m "feat(core): add allowed_tool_categories hard restriction to chat()"
```

---

### Task 3: 心跳记忆归并（节流 + 备份 + 原子写）

**Files:**
- Modify: `agent/scheduler/simple_scheduler.py`
- Test: `agent/tests/test_memory_consolidate.py`（新建）
- Test: `agent/tests/test_heartbeat_check.py`（更新既有测试以适配新增的 `memory_consolidate` 返回字段）

**Interfaces:**
- Consumes: `chat(..., allowed_tool_categories=["file"])`（Task 2 产出）。
- Consumes: `FileTool` 的 `soul/MEMORY.md` 写权限（Task 1 产出，运行时由 `agent.tool_manager` 间接生效，本任务无需直接引用 `FileTool`）。
- Produces: `SimpleTaskScheduler._memory_md_path: Path` 实例属性（默认指向真实 `soul/MEMORY.md`，测试可覆盖为 tmp_path 文件）。
- Produces: `SimpleTaskScheduler._consolidate_memory(self, agent) -> Dict[str, Any]`，返回 `{"ran": False, "reason": "throttled"}` 或 `{"ran": True, "summary": str}`。
- Produces: `heartbeat_check_action` 返回值新增 `"memory_consolidate"` 键（值为上面的字典）。

- [ ] **Step 1: 写失败测试 — `test_memory_consolidate.py`**

```python
"""测试 soul/MEMORY.md 记忆归并的节流/备份/原子写逻辑（TODO-83）。"""
from datetime import datetime, timedelta
from unittest.mock import AsyncMock, MagicMock

import pytest

from scheduler.simple_scheduler import SimpleTaskScheduler


@pytest.fixture
def tmp_tasks_file(tmp_path):
    return tmp_path / "tasks.json"


def make_scheduler(tasks_file, memory_md_path):
    sched = SimpleTaskScheduler(check_interval=60, tasks_file=tasks_file)
    sched._memory_md_path = memory_md_path
    return sched


def make_agent(chat_content: str):
    agent = MagicMock()
    agent.chat = AsyncMock(return_value={"content": chat_content})
    return agent


@pytest.mark.asyncio
async def test_no_existing_file_triggers_llm(tmp_tasks_file, tmp_path):
    mem_path = tmp_path / "MEMORY.md"
    sched = make_scheduler(tmp_tasks_file, mem_path)
    agent = make_agent("NO_CHANGE")

    result = await sched._consolidate_memory(agent)

    assert result["ran"] is True
    agent.chat.assert_called_once()
    _, kwargs = agent.chat.call_args
    assert kwargs["allowed_tool_categories"] == ["file"]
    assert kwargs["use_tools"] is True
    assert "last_consolidated:" in mem_path.read_text(encoding="utf-8")


@pytest.mark.asyncio
async def test_recent_timestamp_throttles_without_calling_llm(tmp_tasks_file, tmp_path):
    mem_path = tmp_path / "MEMORY.md"
    recent = datetime.now() - timedelta(hours=1)
    mem_path.write_text(
        f"<!-- last_consolidated: {recent.isoformat()} -->\n# 精选记忆\n", encoding="utf-8"
    )
    sched = make_scheduler(tmp_tasks_file, mem_path)
    agent = make_agent("NO_CHANGE")

    result = await sched._consolidate_memory(agent)

    assert result == {"ran": False, "reason": "throttled"}
    agent.chat.assert_not_called()


@pytest.mark.asyncio
async def test_stale_timestamp_triggers_llm_and_updates_timestamp(tmp_tasks_file, tmp_path):
    mem_path = tmp_path / "MEMORY.md"
    stale = datetime.now() - timedelta(hours=25)
    mem_path.write_text(
        f"<!-- last_consolidated: {stale.isoformat()} -->\n# 精选记忆\n", encoding="utf-8"
    )
    sched = make_scheduler(tmp_tasks_file, mem_path)
    agent = make_agent("NO_CHANGE")

    result = await sched._consolidate_memory(agent)

    assert result["ran"] is True
    agent.chat.assert_called_once()
    new_text = mem_path.read_text(encoding="utf-8")
    assert stale.isoformat() not in new_text
    assert "# 精选记忆" in new_text


@pytest.mark.asyncio
async def test_consolidation_backs_up_existing_file_before_llm_call(tmp_tasks_file, tmp_path):
    mem_path = tmp_path / "MEMORY.md"
    mem_path.write_text("# 原始内容\n", encoding="utf-8")
    sched = make_scheduler(tmp_tasks_file, mem_path)
    agent = make_agent("NO_CHANGE")

    await sched._consolidate_memory(agent)

    backup_path = mem_path.with_name("MEMORY.md.bak.1")
    assert backup_path.exists()
    assert "# 原始内容" in backup_path.read_text(encoding="utf-8")


@pytest.mark.asyncio
async def test_timestamp_written_even_when_llm_call_fails(tmp_tasks_file, tmp_path):
    mem_path = tmp_path / "MEMORY.md"
    mem_path.write_text("# 精选记忆\n", encoding="utf-8")
    sched = make_scheduler(tmp_tasks_file, mem_path)
    agent = MagicMock()
    agent.chat = AsyncMock(side_effect=RuntimeError("模型挂了"))

    result = await sched._consolidate_memory(agent)

    assert result["ran"] is True
    assert "last_consolidated:" in mem_path.read_text(encoding="utf-8")
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd agent && conda run -n python310 python -m pytest tests/test_memory_consolidate.py -v
```
Expected: 全部 FAIL（`AttributeError: 'SimpleTaskScheduler' object has no attribute '_memory_md_path'/'_consolidate_memory'`）。

- [ ] **Step 3: 实现 — 模块级常量与辅助函数**

在 `agent/scheduler/simple_scheduler.py` 顶部 import 区追加：

```python
import re
import shutil
```

（与现有 `import asyncio / json / os / threading / time` 并列。）

在 `_DEFAULT_TASKS_FILE` 定义之后、`class SimpleTaskScheduler:` 之前插入：

```python
# ── 记忆归并辅助函数（TODO-83）──────────────────────────────────
# soul/MEMORY.md 头部的节流标记，纯由代码读写，不依赖 LLM 维护，保证节流可靠。
_MEMORY_TIMESTAMP_RE = re.compile(r"<!--\s*last_consolidated:\s*([0-9T:.\-]+)\s*-->")
_MEMORY_CONSOLIDATE_THROTTLE_HOURS = 24

_MEMORY_CONSOLIDATE_PROMPT = (
    "现在是一次心跳巡检中的记忆归并环节，不是用户主动发来的消息。"
    "你被授权使用 file 工具读写 soul/MEMORY.md 这一个文件，用于维护你的长期精选记忆。\n\n"
    "⚠️ 安全声明：\n"
    "- 你只能使用 file 工具，且只能操作 soul/MEMORY.md\n"
    "- 你禁止删除该文件、禁止将其改名或移动（即使尝试也会被拒绝）\n"
    "- 你禁止修改 soul/MEMORY.md 之外的任何文件\n\n"
    "请按以下步骤操作：\n"
    "1. 用 file 工具（action=read）读取 soul/MEMORY.md 当前内容\n"
    "2. 基于你对近期对话和长期记忆的了解，判断是否有值得永久记录的新内容。"
    "判定标准：铁律/不可逆的重要决策/已反复验证的规律 → 值得记录；"
    "一次性的临时事件、已被新版取代的旧条目 → 不值得，应删除或跳过\n"
    "3. 如果有值得记录的新内容，用 file 工具（action=write）写回更新后的完整文件，要求：\n"
    "   - 全文不超过 200 行\n"
    "   - 超出时优先合并或删除已被取代的旧条目，而不是简单截断\n"
    "   - 保留文件已有的整体结构（精选记忆 / 重要决策记录 / 主题索引）\n"
    "4. 如果没有值得记录的新内容，什么都不要做，直接回复 NO_CHANGE\n\n"
    "完成后用一句话总结你做了什么（或回复 NO_CHANGE），不要输出其他内容。"
)


def _atomic_write_text(path: Path, content: str) -> None:
    """原子写入：先写临时文件再 replace，避免写入中途崩溃导致文件半截。"""
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(content, encoding="utf-8")
    tmp.replace(path)


def _backup_memory_md(path: Path, keep: int = 5) -> None:
    """写入前做一次轮转备份（.bak.1 最新 ... .bak.N 最旧），保留最近 N 份。

    这个项目的长期记忆此前出现过自毁性 bug（ChromaDB 异常即删库重建），
    LLM 自主重写 MEMORY.md 前留一份备份兜底，代价很小。
    """
    if not path.exists():
        return
    for i in range(keep, 1, -1):
        older = path.with_name(f"{path.name}.bak.{i - 1}")
        newer = path.with_name(f"{path.name}.bak.{i}")
        if older.exists():
            older.replace(newer)
    shutil.copy2(path, path.with_name(f"{path.name}.bak.1"))


def _read_last_consolidated(path: Path) -> Optional[datetime]:
    if not path.exists():
        return None
    try:
        text = path.read_text(encoding="utf-8")
    except Exception:
        return None
    m = _MEMORY_TIMESTAMP_RE.search(text)
    if not m:
        return None
    try:
        return datetime.fromisoformat(m.group(1))
    except ValueError:
        return None


def _write_last_consolidated(path: Path, ts: datetime) -> None:
    """更新（或插入）soul/MEMORY.md 头部的节流时间戳标记。"""
    stamp = f"<!-- last_consolidated: {ts.isoformat()} -->"
    if path.exists():
        text = path.read_text(encoding="utf-8")
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        text = ""
    if _MEMORY_TIMESTAMP_RE.search(text):
        new_text = _MEMORY_TIMESTAMP_RE.sub(stamp, text, count=1)
    else:
        new_text = stamp + "\n" + text
    _atomic_write_text(path, new_text)
```

- [ ] **Step 4: 实现 — `__init__` 新增实例属性**

在 `SimpleTaskScheduler.__init__` 中，紧跟 `self._inference_slot = None    # async context manager` 之后追加：

```python
        # soul/MEMORY.md 路径（TODO-83 记忆归并），由文件位置锚定，不依赖 CWD；
        # 测试通过覆盖此属性指向 tmp_path，避免触碰真实文件。
        self._memory_md_path: Path = Path(__file__).resolve().parent.parent.parent / "soul" / "MEMORY.md"
```

- [ ] **Step 5: 实现 — `_consolidate_memory` 方法**

在 `register_action` 方法之前（即 `_register_builtin_actions` 方法定义之后）插入新的实例方法：

```python
    async def _consolidate_memory(self, agent) -> Dict[str, Any]:
        """记忆归并（TODO-83）：节流读取 soul/MEMORY.md 头部时间戳标记，超过
        _MEMORY_CONSOLIDATE_THROTTLE_HOURS 才触发一次仅授权 file 工具分类的 LLM 调用，
        让其自主判断是否要把近期值得永久记录的内容合并进文件。

        节流时间戳的读写完全由本方法（而非 LLM）负责，保证可靠，不依赖模型是否记得维护；
        写入前先做一次轮转备份，写回时间戳用原子替换，防止写入中途崩溃损坏文件。
        """
        path = self._memory_md_path
        last = _read_last_consolidated(path)
        now = datetime.now()
        if last is not None and (now - last) < timedelta(hours=_MEMORY_CONSOLIDATE_THROTTLE_HOURS):
            return {"ran": False, "reason": "throttled"}

        try:
            _backup_memory_md(path)
        except Exception as e:
            logger.warning(f"[memory_consolidate] 备份失败（继续执行）: {e}")

        try:
            result = await agent.chat(
                _MEMORY_CONSOLIDATE_PROMPT,
                use_tools=True,
                use_memory=True,
                skip_cache=True,
                allowed_tool_categories=["file"],
            )
            summary = (result.get("content", "") if isinstance(result, dict) else str(result)).strip()
        except Exception as e:
            logger.error(f"[memory_consolidate] LLM 归并调用失败: {e}")
            summary = f"<error: {e}>"

        try:
            _write_last_consolidated(path, now)
        except Exception as e:
            logger.warning(f"[memory_consolidate] 写回节流时间戳失败: {e}")

        try:
            if path.exists():
                n_lines = len(path.read_text(encoding="utf-8").splitlines())
                if n_lines > 180:
                    logger.warning(f"[memory_consolidate] soul/MEMORY.md 已达 {n_lines} 行，接近 200 行上限")
        except Exception:
            pass

        logger.info(f"[memory_consolidate] 归并完成: {summary[:80]}")
        return {"ran": True, "summary": summary[:200]}
```

- [ ] **Step 6: 运行新测试确认通过**

```bash
cd agent && conda run -n python310 python -m pytest tests/test_memory_consolidate.py -v
```
Expected: 5 passed

- [ ] **Step 7: 接入 `heartbeat_check_action`**

把现有 `heartbeat_check_action` 函数体（从 `agent = self._agent` 到函数末尾 `return {"success": True, "sent": True, ...}`）的判定逻辑包进内部函数 `_run_decision()`，再统一附加 `memory_consolidate` 结果。完整替换 `heartbeat_check_action` 为：

```python
        async def heartbeat_check_action(
            receiver_id: str,
            receive_id_type: str = "open_id",
            user_id: str = "java-service",
            quiet_hour_start: int = _QUIET_HOUR_START,
            quiet_hour_end: int = _QUIET_HOUR_END,
        ):
            """心跳巡检：让 LLM 判断当前是否需要主动联系用户，需要才通过 im_message 发送；
            同时顺带触发一次节流的记忆归并（TODO-83，见 self._consolidate_memory）。

            与 llm_generate 的区别：llm_generate 总是把结果推给用户；heartbeat_check 默认沉默，
            只有 LLM 明确给出 SPEAK 判定时才真正发送消息，避免无意义的主动打扰。
            生成判定文本时固定使用 channel="feishu_im"，保证一旦决定发送，内容已经是 IM 克制风格
            （不会带出私密档案段），无需在发送前二次过滤。
            """
            _now_hour = datetime.now().hour
            _in_quiet = (
                _now_hour >= quiet_hour_start or _now_hour < quiet_hour_end
                if quiet_hour_start > quiet_hour_end
                else quiet_hour_start <= _now_hour < quiet_hour_end
            )
            if _in_quiet:
                logger.debug(f"[heartbeat_check] 安静时段（{quiet_hour_start}:00-{quiet_hour_end}:00），跳过")
                return {"success": True, "sent": False, "reason": "quiet_hours"}

            agent = self._agent
            if agent is None:
                return {"success": False, "error": "agent not initialized"}

            async def _run_decision() -> Dict[str, Any]:
                try:
                    result = await agent.chat(
                        _HEARTBEAT_DECISION_PROMPT,
                        use_tools=False,
                        use_memory=True,
                        skip_cache=True,
                        user_id=user_id,
                        channel="feishu_im",
                    )
                    content = (result.get("content", "") if isinstance(result, dict) else str(result)).strip()
                except Exception as e:
                    logger.error(f"[heartbeat_check] LLM 判定调用失败: {e}")
                    return {"success": False, "error": str(e)}

                if not content.upper().startswith("SPEAK:"):
                    if content.upper() != "SILENT":
                        logger.warning(f"[heartbeat_check] LLM 输出格式不符合约定，按沉默处理: {content[:80]}")
                    return {"success": True, "sent": False, "reason": "silent"}

                message = content.split(":", 1)[1].strip()
                if not message:
                    return {"success": True, "sent": False, "reason": "empty_speak"}

                _tm = self._tool_manager
                if _tm is None:
                    from tools.tool_manager import tool_manager as _global_tm
                    _tm = _global_tm
                im_tool = _tm.get_tool("im_message")
                if im_tool is None:
                    logger.warning("[heartbeat_check] im_message 工具未注册，无法发送，判定结果丢弃")
                    return {"success": False, "error": "im_message tool not registered", "message": message}

                try:
                    _send_result = im_tool(
                        receiver_id=receiver_id,
                        msg_type="text",
                        content={"text": message},
                        receive_id_type=receive_id_type,
                    )
                    # BaseTool.__call__ 内部吞掉异常，包装成 ToolResult(success=False, error=...)，
                    # 不会向外抛异常，必须显式检查 success 字段才能感知发送失败。
                    if hasattr(_send_result, "success") and not _send_result.success:
                        raise RuntimeError(getattr(_send_result, "error", "未知错误"))
                except Exception as e:
                    logger.error(f"[heartbeat_check] im_message 发送失败: {e}")
                    return {"success": False, "error": str(e), "message": message}

                ts = datetime.now().isoformat()
                try:
                    agent.memory.store(message, category="task", metadata={"source": "heartbeat_check", "role": "assistant", "timestamp": ts})
                except Exception as _me:
                    logger.warning(f"[heartbeat_check] 写入短期记忆失败: {_me}")
                logger.info(f"[heartbeat_check] 主动联系已发送，长度 {len(message)}")
                return {"success": True, "sent": True, "message": message, "timestamp": ts}

            decision = await _run_decision()
            decision["memory_consolidate"] = await self._consolidate_memory(agent)
            return decision
```

- [ ] **Step 8: 更新既有 `test_heartbeat_check.py` 以适配新增字段**

`make_scheduler` 改为接受可选的 `memory_md_path`：

```python
def make_scheduler(tasks_file, memory_md_path=None):
    sched = SimpleTaskScheduler(check_interval=60, tasks_file=tasks_file)
    if memory_md_path is not None:
        sched._memory_md_path = memory_md_path
    return sched
```

`test_quiet_hours_skips_without_calling_llm` 不变（早返回，不受影响）。

`test_silent_decision_does_not_send` 改为（加 `tmp_path` 参数，断言改为分字段检查，不再用整体相等）：

```python
@pytest.mark.asyncio
async def test_silent_decision_does_not_send(tmp_tasks_file, tmp_path):
    sched = make_scheduler(tmp_tasks_file, tmp_path / "MEMORY.md")
    sched._agent = make_agent("SILENT")
    im_tool = MagicMock()
    sched._tool_manager = MagicMock()
    sched._tool_manager.get_tool.return_value = im_tool

    result = await sched.actions["heartbeat_check"](
        receiver_id="ou_123",
        quiet_hour_start=5, quiet_hour_end=5,  # 永不安静
    )

    assert result["success"] is True
    assert result["sent"] is False
    assert result["reason"] == "silent"
    assert result["memory_consolidate"]["ran"] is True
    im_tool.assert_not_called()
```

`test_speak_decision_sends_via_im_message` 加 `tmp_path` 参数，并把末尾的 `call_args`（默认取最后一次调用）改为 `call_args_list[0]`（因为现在 `agent.chat` 会被调用两次：判定 + 归并）：

```python
@pytest.mark.asyncio
async def test_speak_decision_sends_via_im_message(tmp_tasks_file, tmp_path):
    sched = make_scheduler(tmp_tasks_file, tmp_path / "MEMORY.md")
    sched._agent = make_agent("SPEAK: 好久没聊了，最近还好吗？")
    im_tool = MagicMock()
    sched._tool_manager = MagicMock()
    sched._tool_manager.get_tool.return_value = im_tool

    result = await sched.actions["heartbeat_check"](
        receiver_id="ou_123",
        receive_id_type="open_id",
        quiet_hour_start=5, quiet_hour_end=5,
    )

    assert result["success"] is True
    assert result["sent"] is True
    assert result["message"] == "好久没聊了，最近还好吗？"
    im_tool.assert_called_once_with(
        receiver_id="ou_123",
        msg_type="text",
        content={"text": "好久没聊了，最近还好吗？"},
        receive_id_type="open_id",
    )
    sched._agent.memory.store.assert_called_once()

    # 判定调用（第一次 agent.chat 调用）必须走 feishu_im 渠道，
    # 保证生成内容本身就是 IM 克制风格；第二次调用是记忆归并，channel 不受此约束。
    _, decision_kwargs = sched._agent.chat.call_args_list[0]
    assert decision_kwargs["channel"] == "feishu_im"
```

`test_speak_decision_without_im_tool_registered` 与 `test_im_tool_failure_result_is_detected` 各加 `tmp_path` 参数并传入 `make_scheduler(tmp_tasks_file, tmp_path / "MEMORY.md")`（断言内容不变，已是分字段检查，不受新增键影响）。

`test_no_agent_returns_error` 不变（早返回，不受影响）。

- [ ] **Step 9: 运行全部相关测试确认通过**

```bash
cd agent && conda run -n python310 python -m pytest tests/test_heartbeat_check.py tests/test_memory_consolidate.py tests/test_file_tool_whitelist.py tests/test_agent_core.py -v
```
Expected: 全部 PASS

- [ ] **Step 10: 跑全量 Python 测试套件确认无回归**

```bash
cd agent && conda run -n python310 python -m pytest tests/ -q
```
Expected: 仅 `test_cron_scheduler.py::TestCronShouldRun::test_every_minute_not_yet` 可能因既有时序竞争 flake 失败（与本次改动无关），其余全部 PASS。

- [ ] **Step 11: Commit**

```bash
git add agent/scheduler/simple_scheduler.py agent/tests/test_memory_consolidate.py agent/tests/test_heartbeat_check.py
git commit -m "feat(scheduler): heartbeat-triggered memory consolidation for soul/MEMORY.md"
```

---

### Task 4: soul/MEMORY.md 加入节流标记 + 更新 TODOS.md

**Files:**
- Modify: `soul/MEMORY.md`
- Modify: `TODOS.md`

**Interfaces:**
- Consumes: 无（纯文档/数据文件改动）。

- [ ] **Step 1: 给 `soul/MEMORY.md` 加节流标记行**

读取当前内容，在文件最顶部插入一行（保留原有全部内容不变）：

```markdown
<!-- last_consolidated: 1970-01-01T00:00:00 -->
# 精选记忆

## 当前项目
...（原有内容不变）
```

用一个早于当前时间 24 小时以上的时间戳（如 `1970-01-01T00:00:00`），确保部署后第一次心跳会真正触发一次归并，而不是被节流跳过。

- [ ] **Step 2: 更新 `TODOS.md`**

把 TODO-83 的标题和正文改为已完成状态（参照仓库里其他 `~~TODO-N~~ ✅ 已完成` 条目的格式），简述：FileTool 单文件白名单 + `allowed_tool_categories` 硬限制 + 心跳触发的节流/备份/原子写记忆归并，附今天的提交记录。

- [ ] **Step 3: Commit**

```bash
git add soul/MEMORY.md TODOS.md
git commit -m "docs: mark TODO-83 done, seed soul/MEMORY.md throttle marker"
```

---

## Self-Review Notes

- **Spec coverage**：白名单+禁删禁移（Task 1）、硬限制工具分类（Task 2）、节流+备份+原子写+心跳接入（Task 3）、文档收尾（Task 4）——评审中"接受"的全部条目均有对应任务覆盖；"不接受"的条目（独立 prompt 文件、事件计数双触发、黑名单、checksum、并发心跳测试、RFC3339）均未出现在任何任务中，符合此前与用户对齐的范围。
- **Type consistency**：`_consolidate_memory(self, agent) -> Dict[str, Any]` 在 Task 3 Step 5 定义，Step 7/8 的调用与测试断言均使用相同的 `ran`/`reason`/`summary` 键名，未出现命名漂移。`allowed_tool_categories` 参数名在 Task 2 的 `chat()`、`_call_model_with_tools()` 与 Task 3 的调用处保持一致。
- **Placeholder scan**：未发现 TBD/未实现占位；所有步骤均给出完整可运行代码。

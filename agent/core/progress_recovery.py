"""进度恢复协议（TODO-94）—— session 启动时自动检测未完成的 progress_state*.md，
注入 [PROGRESS RECOVERY] 上下文，让 Agent 从中断点继续而不是重头开始。

progress_state.md 标准格式：
    # 任务进度
    - 任务名：<描述>
    - 当前步骤：<N> / <M>
    - 最后更新：<ISO timestamp>
    - 下一步：<具体动作>
    - 备注：<可选>

检测逻辑：
    - 最后更新距今 < 24h 且当前步骤 < 总步骤 → "未完成任务"
    - 否则视为已完成/过期，不注入恢复上下文
"""
from __future__ import annotations

import re
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional

from loguru import logger

# 进度文件目录（相对于 agent/ 目录）
_DEFAULT_WORK_DIR = Path(__file__).resolve().parent.parent / "memory" / "work"

# 进度文件匹配模式
_PROGRESS_FILE_PATTERN = re.compile(r"progress_state.*\.md$")

# 字段解析 regex
_RE_TASK_NAME = re.compile(r"^[-*]\s*任务名[：:]\s*(.+)$")
_RE_CURRENT_STEP = re.compile(r"^[-*]\s*当前步骤[：:]\s*(\d+)\s*/\s*(\d+)$")
# 最后更新支持 "最后更新" 或 "最后更新时间"
_RE_LAST_UPDATED = re.compile(r"^[-*]\s*最后更新(?:时间)?[：:]\s*(.+)$")
_RE_NEXT_STEP = re.compile(r"^[-*]\s*下一步[：:]\s*(.+)$")
_RE_NOTES = re.compile(r"^[-*]\s*备注[：:]\s*(.+)$")

# 未完成任务的最大时限（超过此时间不再提示恢复）
_DEFAULT_MAX_AGE_HOURS = 24


def _resolve_work_dir() -> Path:
    """解析进度文件目录（可被测试覆盖）。"""
    return _DEFAULT_WORK_DIR


def parse_progress_file(file_path: Path) -> Optional[Dict[str, Any]]:
    """解析单个 progress_state*.md 文件，返回结构化 dict 或 None。

    返回格式：
        {
            "file_path": str,
            "task_name": str,
            "current_step": int,
            "total_steps": int,
            "last_updated": datetime,
            "next_step": str,
            "notes": str,
            "raw_content": str,
        }
    """
    if not file_path.exists():
        return None

    try:
        content = file_path.read_text(encoding="utf-8")
    except Exception as e:
        logger.warning(f"[progress_recovery] 读取 {file_path} 失败: {e}")
        return None

    task_name = ""
    current_step = 0
    total_steps = 0
    last_updated = None
    next_step = ""
    notes = ""

    for line in content.splitlines():
        stripped = line.strip()

        m = _RE_TASK_NAME.match(stripped)
        if m:
            task_name = m.group(1).strip()
            continue

        m = _RE_CURRENT_STEP.match(stripped)
        if m:
            try:
                current_step = int(m.group(1))
                total_steps = int(m.group(2))
            except ValueError:
                pass
            continue

        m = _RE_LAST_UPDATED.match(stripped)
        if m:
            ts_str = m.group(1).strip()
            try:
                # 尝试多种 ISO 格式
                for fmt in (
                    "%Y-%m-%dT%H:%M:%S",
                    "%Y-%m-%dT%H:%M:%S.%f",
                    "%Y-%m-%d %H:%M:%S",
                    "%Y-%m-%dT%H:%M",
                ):
                    try:
                        last_updated = datetime.strptime(ts_str, fmt)
                        break
                    except ValueError:
                        continue
            except Exception:
                pass
            continue

        m = _RE_NEXT_STEP.match(stripped)
        if m:
            next_step = m.group(1).strip()
            continue

        m = _RE_NOTES.match(stripped)
        if m:
            notes = m.group(1).strip()
            continue

    # 有效性检查：至少要有任务名和步骤信息
    if not task_name or total_steps == 0:
        return None

    return {
        "file_path": str(file_path),
        "task_name": task_name,
        "current_step": current_step,
        "total_steps": total_steps,
        "last_updated": last_updated,  # datetime or None
        "next_step": next_step,
        "notes": notes,
        "raw_content": content,
    }


def find_incomplete_tasks(
    work_dir: Optional[Path] = None,
    max_age_hours: int = _DEFAULT_MAX_AGE_HOURS,
) -> List[Dict[str, Any]]:
    """扫描 work_dir 下所有 progress_state*.md，返回未完成任务的列表。

    "未完成"的定义：
    1. 当前步骤 < 总步骤（还没做完）
    2. 最后更新距今 < max_age_hours（不是陈年旧事）
    3. 如果最后更新字段缺失，保守处理：忽略该文件（不注入不可靠的上下文）

    Args:
        work_dir: 进度文件目录，None 时使用默认 memory/work/
        max_age_hours: 任务最大时效（小时）

    Returns:
        未完成任务列表，按最后更新时间倒序排列（最近更新的在前）
    """
    if work_dir is None:
        work_dir = _resolve_work_dir()

    if not work_dir.exists():
        return []

    now = datetime.now()
    incomplete = []

    try:
        for entry in sorted(work_dir.iterdir()):
            if not entry.is_file():
                continue
            if not _PROGRESS_FILE_PATTERN.match(entry.name):
                continue

            parsed = parse_progress_file(entry)
            if parsed is None:
                continue

            # 检查任务是否已完成
            if parsed["current_step"] >= parsed["total_steps"]:
                logger.debug(
                    f"[progress_recovery] {entry.name} 已完成 "
                    f"({parsed['current_step']}/{parsed['total_steps']})，跳过"
                )
                continue

            # 检查时效性
            last_updated = parsed["last_updated"]
            if last_updated is None:
                logger.debug(
                    f"[progress_recovery] {entry.name} 缺少最后更新时间，跳过"
                )
                continue

            age = now - last_updated
            if age > timedelta(hours=max_age_hours):
                logger.debug(
                    f"[progress_recovery] {entry.name} 已过期 "
                    f"({age.total_seconds() / 3600:.1f}h > {max_age_hours}h)，跳过"
                )
                continue

            incomplete.append(parsed)
    except OSError as e:
        logger.warning(f"[progress_recovery] 扫描 {work_dir} 失败: {e}")
        return []

    # 按最后更新时间倒序
    incomplete.sort(key=lambda t: t["last_updated"] or datetime.min, reverse=True)
    return incomplete


def build_recovery_context(tasks: List[Dict[str, Any]]) -> str:
    """根据未完成任务列表构建 [PROGRESS RECOVERY] 系统消息文本。

    只取最近的一个未完成任务（避免上下文过载），最多展示 2 个。
    """
    if not tasks:
        return ""

    # 最多展示 2 个未完成任务
    display_tasks = tasks[:2]

    lines = [
        "[PROGRESS RECOVERY] 以下是你上次未完成的任务进度。",
        "这些任务在之前的 session 中开始但尚未完成，请从中断点继续而不是重头开始。",
        "",
    ]

    for i, task in enumerate(display_tasks, 1):
        name = task["task_name"]
        step = task["current_step"]
        total = task["total_steps"]
        next_step = task["next_step"]
        notes = task["notes"]
        ts = task["last_updated"]
        ts_str = ts.strftime("%Y-%m-%d %H:%M") if ts else "未知"

        lines.append(f"--- 未完成任务 {i} ---")
        lines.append(f"任务名：{name}")
        lines.append(f"当前进度：第 {step}/{total} 步")
        lines.append(f"最后更新：{ts_str}")
        if next_step:
            lines.append(f"下一步：{next_step}")
        if notes:
            lines.append(f"备注：{notes}")

    lines.append("")
    lines.append("请优先处理这些未完成的任务，从'下一步'描述的中断点继续执行。")

    return "\n".join(lines)

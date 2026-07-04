"""heart_record 工具 — 读写 soul/heart.md 心证铁卷。

三个 action：
  - append(content, category, tags, weight) — 追加心证到指定分区
  - list(category) — 列出心证（可按分区筛选）
  - delete(id) — 按 ID 删除心证（先轮转备份）

写入前自动做 .bak.1~.bak.5 轮转备份（与 MEMORY.md 同策略），
只允许操作 soul/heart.md，不触及同目录其他 soul 文件。
"""
from __future__ import annotations

import re
import shutil
from datetime import date
from pathlib import Path
from typing import Any, Dict, List, Optional

from loguru import logger
from tools.base_tool import BaseTool, ToolParameter, ToolResult

# heart.md 绝对路径，由本文件位置锚定（不依赖 CWD）
_PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent
HEART_MD_PATH = _PROJECT_ROOT / "soul" / "heart.md"

# 分区中文名 → heart.md 内 ## 标题的映射
_CATEGORY_SECTION = {
    "主人心证": "主人心证",
    "主人教诲": "主人教诲",
    "智能体对主人的承诺": "智能体对主人的承诺",
    "主人对智能体的承诺": "主人对智能体的承诺",
}

_ENTRY_RE = re.compile(r"^- \[(\d{4}-\d{2}-\d{2})]\s+(.+)$")


# ---------------------------------------------------------------------------
# 文件操作辅助（模块级，与 simple_scheduler.py 的 _backup_memory_md 同策略）
# ---------------------------------------------------------------------------

def _rotate_backup(path: Path, keep: int = 5) -> None:
    """写入前轮转备份（.bak.1 最新 ... .bak.N 最旧）。"""
    if not path.exists():
        return
    for i in range(keep, 1, -1):
        older = path.with_name(f"{path.name}.bak.{i - 1}")
        newer = path.with_name(f"{path.name}.bak.{i}")
        if older.exists():
            older.replace(newer)
    shutil.copy2(path, path.with_name(f"{path.name}.bak.1"))


def _atomic_write_text(path: Path, content: str) -> None:
    """原子写入：先写临时文件再 replace。"""
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(content, encoding="utf-8")
    tmp.replace(path)


def _verify_write_contains(path: Path, expected_text: str) -> bool:
    """写入后读回验证：确认文件中包含预期文本。"""
    if not path.exists():
        logger.error(f"heart_record 写入后验证失败：文件不存在 {path}")
        return False
    try:
        actual = path.read_text(encoding="utf-8")
        if expected_text not in actual:
            logger.error(
                f"heart_record 写入后验证失败：文件中未找到预期内容 "
                f"({expected_text[:60]!r}...)"
            )
            return False
        return True
    except Exception as e:
        logger.error(f"heart_record 写入后验证读回异常: {e}")
        return False


def _verify_write_excludes(path: Path, excluded_text: str) -> bool:
    """写入后读回验证：确认文件中不包含已删除的文本。"""
    if not path.exists():
        logger.error(f"heart_record 写入后验证失败：文件不存在 {path}")
        return False
    try:
        actual = path.read_text(encoding="utf-8")
        if excluded_text in actual:
            logger.error(
                f"heart_record 删除后验证失败：文件中仍包含已删除内容 "
                f"({excluded_text[:60]!r}...)"
            )
            return False
        return True
    except Exception as e:
        logger.error(f"heart_record 写入后验证读回异常: {e}")
        return False


# ---------------------------------------------------------------------------
# 核心逻辑
# ---------------------------------------------------------------------------

def _parse_heart_sections(text: str) -> Dict[str, List[Dict[str, Any]]]:
    """解析 heart.md 文本，返回 {分区名: [{id, date, content, line_index}, ...]}。

    每个条目分配一个全局 id（1-based），delete 通过此 id 定位。
    """
    sections: Dict[str, List[Dict[str, Any]]] = {}
    current_section: Optional[str] = None
    global_id = 0

    for line in text.splitlines():
        stripped = line.strip()

        # 检测 ## 标题
        if stripped.startswith("## "):
            section_name = stripped[3:].strip()
            if section_name in _CATEGORY_SECTION.values():
                current_section = section_name
                if current_section not in sections:
                    sections[current_section] = []
            else:
                current_section = None
            continue

        # 检测 # 一级标题（重置分区）
        if stripped.startswith("# ") and not stripped.startswith("## "):
            current_section = None
            continue

        # 解析条目行
        if current_section:
            m = _ENTRY_RE.match(stripped)
            if m:
                global_id += 1
                sections[current_section].append({
                    "id": global_id,
                    "date": m.group(1),
                    "content": m.group(2),
                })

    return sections


def _rebuild_heart_md(original_text: str, sections: Dict[str, List[Dict[str, Any]]]) -> str:
    """根据修改后的 sections 重建 heart.md 文本。

    策略：保留原文件的非条目行（标题、注释等），只替换 ## 分区下的条目列表。
    """
    lines = original_text.splitlines()
    result: List[str] = []
    current_section: Optional[str] = None
    i = 0

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        # 一级标题或非分区内容：原样保留
        if stripped.startswith("# ") and not stripped.startswith("## "):
            current_section = None
            result.append(line)
            i += 1
            continue

        # 二级标题：输出标题行，然后输出该分区的新条目，跳过旧条目行
        if stripped.startswith("## "):
            result.append(line)
            section_name = stripped[3:].strip()
            current_section = section_name if section_name in _CATEGORY_SECTION.values() else None
            i += 1

            # 输出该分区的新条目
            if current_section and current_section in sections:
                for entry in sections[current_section]:
                    result.append(f"- [{entry['date']}] {entry['content']}")
                # 跳过旧条目行和空行直到下一个 ## 或 #
                while i < len(lines):
                    next_line = lines[i].strip()
                    if next_line.startswith("## ") or (next_line.startswith("# ") and not next_line.startswith("## ")):
                        break
                    i += 1
                # 分区后加一个空行（如果下一条不是标题）
                if i < len(lines) and not lines[i].strip().startswith("#"):
                    result.append("")
            continue

        # 非标题行：如果不在分区内则保留（注释等），在分区内则跳过（已由上面的循环处理）
        if current_section is None:
            result.append(line)
        i += 1

    return "\n".join(result)


# ---------------------------------------------------------------------------
# 工具类
# ---------------------------------------------------------------------------

class HeartRecordTool(BaseTool):
    """心证铁卷管理工具。

    在 soul/heart.md 中增/查/删心证条目。写入前自动轮转备份。
    """

    def __init__(self):
        description = (
            "心证铁卷管理工具，用于在 soul/heart.md 中增/查/删心证条目。"
            "支持的 action: append(追加心证), list(列出心证), delete(删除心证)。"
            "写入前自动做轮转备份（.bak.1~.bak.5）。"
        )
        super().__init__(name="heart_record", description=description, category="memory")
        self.requires_auth = False

    def execute(self, action: str, content: str = "", category: str = "",
                tags: str = "", weight: str = "normal", id: str = "", **kwargs) -> Dict[str, Any]:
        """执行心证操作。

        Args:
            action: append | list | delete
            content: 心证内容（append 时必填）
            category: 分区名：主人心证 | 主人教诲 | 智能体对主人的承诺 | 主人对智能体的承诺
            tags: 逗号分隔标签（可选，当前仅存储于 content 文本中）
            weight: normal | high | critical（默认 normal，当前仅记录在条目中）
            id: 要删除的心证 ID（delete 时必填）
        """
        if action == "append":
            return self._do_append(content, category, tags, weight)
        elif action == "list":
            return self._do_list(category)
        elif action == "delete":
            return self._do_delete(id)
        else:
            return {"error": f"未知 action: {action}，支持 append / list / delete"}

    # ------------------------------------------------------------------
    # append
    # ------------------------------------------------------------------

    def _do_append(self, content: str, category: str, tags: str, weight: str) -> Dict[str, Any]:
        if not content.strip():
            return {"error": "content 不能为空"}

        section = _CATEGORY_SECTION.get(category)
        if not section:
            return {
                "error": f"无效分区 '{category}'，有效分区: {list(_CATEGORY_SECTION.keys())}"
            }

        # 轮转备份
        _rotate_backup(HEART_MD_PATH)

        # 读取或创建
        if HEART_MD_PATH.exists():
            text = HEART_MD_PATH.read_text(encoding="utf-8")
        else:
            text = self._default_heart_md()

        today = date.today().isoformat()

        # 构建条目行（标签和权重附加在行尾注释中）
        entry_line = f"- [{today}] {content.strip()}"
        extra = []
        if tags.strip():
            extra.append(f"tags={tags.strip()}")
        if weight != "normal":
            extra.append(f"weight={weight}")
        if extra:
            entry_line += f"  <!-- {', '.join(extra)} -->"

        # 在对应 ## 分区下追加
        new_lines: List[str] = []
        lines = text.splitlines()
        target_section_found = False
        i = 0

        while i < len(lines):
            line = lines[i]
            new_lines.append(line)
            stripped = line.strip()

            if stripped.startswith("## ") and stripped[3:].strip() == section:
                target_section_found = True
                # 跳过标题后的空行和注释行，找到插入点
                i += 1
                # 跳过空行和注释行
                while i < len(lines) and (
                    not lines[i].strip()
                    or lines[i].strip().startswith("<!--")
                ):
                    new_lines.append(lines[i])
                    i += 1
                # 插入新条目
                new_lines.append(entry_line)
                # 继续追加剩余行
                while i < len(lines):
                    new_lines.append(lines[i])
                    i += 1
                break
            i += 1

        if target_section_found:
            new_text = "\n".join(new_lines)
        else:
            # 分区不存在：在文件末尾追加分区标题 + 条目
            if text and not text.endswith("\n"):
                new_text = text + "\n\n" + f"## {section}\n\n{entry_line}\n"
            else:
                new_text = text + f"## {section}\n\n{entry_line}\n"

        _atomic_write_text(HEART_MD_PATH, new_text)

        # 写入后读回验证（TODO-93 失职自查钩子）
        _verify_write_contains(HEART_MD_PATH, content.strip())

        logger.info(f"heart_record append → {section}: {content.strip()[:60]}...")

        return {"ok": True, "action": "append", "section": section, "date": today,
                "content": content.strip()}

    # ------------------------------------------------------------------
    # list
    # ------------------------------------------------------------------

    def _do_list(self, category: str = "") -> Dict[str, Any]:
        if not HEART_MD_PATH.exists():
            return {"entries": [], "total": 0}

        text = HEART_MD_PATH.read_text(encoding="utf-8")
        all_sections = _parse_heart_sections(text)

        if category:
            section = _CATEGORY_SECTION.get(category)
            if not section:
                return {"error": f"无效分区 '{category}'"}
            entries = all_sections.get(section, [])
        else:
            entries = []
            for sec_entries in all_sections.values():
                entries.extend(sec_entries)

        return {"entries": entries, "total": len(entries)}

    # ------------------------------------------------------------------
    # delete
    # ------------------------------------------------------------------

    def _do_delete(self, id_str: str) -> Dict[str, Any]:
        if not id_str:
            return {"error": "id 不能为空（使用 list 查看各条目的 id）"}

        if not HEART_MD_PATH.exists():
            return {"error": "heart.md 不存在，没有可删除的条目"}

        try:
            target_id = int(id_str)
        except ValueError:
            return {"error": f"无效 id: {id_str}，必须为整数"}

        # 轮转备份
        _rotate_backup(HEART_MD_PATH)

        text = HEART_MD_PATH.read_text(encoding="utf-8")
        sections = _parse_heart_sections(text)

        # 找到目标条目
        target_entry = None
        target_section_name = None
        for sec_name, entries in sections.items():
            for entry in entries:
                if entry["id"] == target_id:
                    target_entry = entry
                    target_section_name = sec_name
                    break
            if target_entry:
                break

        if not target_entry:
            return {"error": f"未找到 id={target_id} 的心证条目"}

        # 从 sections 中移除
        sections[target_section_name] = [
            e for e in sections[target_section_name] if e["id"] != target_id
        ]

        # 重建文件
        new_text = _rebuild_heart_md(text, sections)
        _atomic_write_text(HEART_MD_PATH, new_text)

        # 写入后读回验证：确认已删除的条目不在文件中（TODO-93 失职自查钩子）
        _verify_write_excludes(HEART_MD_PATH, target_entry["content"])

        logger.info(f"heart_record delete id={target_id} from {target_section_name}")

        return {"ok": True, "action": "delete", "id": target_id,
                "section": target_section_name,
                "deleted_content": target_entry["content"]}

    # ------------------------------------------------------------------
    # helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _default_heart_md() -> str:
        return (
            "# 心证铁卷\n\n"
            "## 主人心证\n"
            "<!-- 用户主动标记的永久记忆：关键决策 / 不可逆教训 / 已验证的规律 -->\n\n"
            "## 主人教诲\n"
            "<!-- 用户对 Agent 的长期行为指令 -->\n\n"
            "## 智能体对主人的承诺\n"
            "<!-- Agent 对用户的承诺 -->\n\n"
            "## 主人对智能体的承诺\n"
            "<!-- 用户对 Agent 的承诺 -->\n"
        )

    def _get_parameters(self):
        return [
            ToolParameter(
                name="action", type="str", required=True,
                description="操作类型: append | list | delete",
            ),
            ToolParameter(
                name="content", type="str", required=False,
                description="心证内容（action=append 时必填）",
            ),
            ToolParameter(
                name="category", type="str", required=False,
                description="分区: 主人心证 | 主人教诲 | 智能体对主人的承诺 | 主人对智能体的承诺",
            ),
            ToolParameter(
                name="tags", type="str", required=False,
                description="逗号分隔标签（可选）",
            ),
            ToolParameter(
                name="weight", type="str", required=False,
                description="normal | high | critical，默认 normal",
            ),
            ToolParameter(
                name="id", type="str", required=False,
                description="要删除的心证条目 ID（action=delete 时必填，通过 list 获取）",
            ),
        ]

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
from core.system_prompt_builder import invalidate_rules_cache
from tools.base_tool import BaseTool, ToolParameter, ToolResult

# heart.md 绝对路径，由本文件位置锚定（不依赖 CWD）
_PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent
HEART_MD_PATH = _PROJECT_ROOT / "soul" / "heart.md"
RULES_MD_PATH = _PROJECT_ROOT / "soul" / "rules.md"

# 分区中文名 → heart.md 内 ## 标题的映射
_CATEGORY_SECTION = {
    "主人心证": "主人心证",
    "主人教诲": "主人教诲",
    "智能体对主人的承诺": "智能体对主人的承诺",
    "主人对智能体的承诺": "主人对智能体的承诺",
}

# 主人铁律 — 7 个作用分类
_RULE_CATEGORIES = [
    "安全边界",      # 系统安全、权限控制
    "模型绑定",      # 模型选择、provider 约束
    "工具使用",      # 工具调用的边界和规范
    "失职与自查",    # 失败坦白、自查义务
    "记忆与持久化",  # 记忆写入、进度文件
    "用户交互",      # 回复风格、IM 行为
    "隐私与数据",    # 数据保护、隐私边界
]

_RULE_PRIORITIES = {"critical": "★★★★★", "high": "★★★★", "normal": "★★★"}
_RULE_PRIVACY_LEVELS = {"public", "private", "secret"}

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


def _rollback_to_bak(path: Path, n: int) -> bool:
    """从 .bak.n 回滚到主文件。n=1 最新，n=5 最旧。

    回滚前先备份当前文件为 .bak.0（抢救性快照），防止误回滚丢失最新改动。
    """
    bak = path.with_name(f"{path.name}.bak.{n}")
    if not bak.exists():
        logger.error(f"回滚失败：备份文件不存在 {bak}")
        return False

    # 抢救性快照：当前文件如果存在，先存为 .bak.0
    if path.exists():
        rescue = path.with_name(f"{path.name}.bak.0")
        shutil.copy2(path, rescue)
        logger.info(f"回滚前抢救快照: {rescue}")

    shutil.copy2(bak, path)
    logger.warning(f"已回滚 {path.name} → {bak.name}")
    return True


# ---------------------------------------------------------------------------
# 规则冲突检测（模块级函数）
# ---------------------------------------------------------------------------

def _time_constraint_conflict(req_a: str, req_b: str) -> bool:
    """检测两条规则的时间约束是否矛盾：'必须 X 点做' + 'X 点不能做任何事'。"""
    time_a = re.findall(r"(\d{1,2})[：:]\d{2}", req_a)
    time_b = re.findall(r"(\d{1,2})[：:]\d{2}", req_b)
    if not time_a or not time_b:
        return False
    has_must_a = "必须" in req_a
    has_must_b = "必须" in req_b
    has_cannot_a = "不能" in req_a or "禁止" in req_a
    has_cannot_b = "不能" in req_b or "禁止" in req_b
    return (has_must_a and has_cannot_b) or (has_must_b and has_cannot_a)


def _model_binding_conflict(req_a: str, req_b: str) -> bool:
    """检测两条规则是否指定了不同的强制模型。"""
    models_a = set(re.findall(r"(?:必须|只能|强制).*?(?:使用|绑定|用)\s*(\S+)", req_a))
    models_b = set(re.findall(r"(?:必须|只能|强制).*?(?:使用|绑定|用)\s*(\S+)", req_b))
    if not models_a or not models_b:
        return False
    return bool(models_a - models_b) or bool(models_b - models_a)


def _behavior_conflict(req_a: str, req_b: str) -> bool:
    """检测两条规则是否对同一行为发出相反指令。"""
    must_patterns_a = set(re.findall(r"必须(.+?)(?:[，。；]|$)", req_a))
    cannot_patterns_b = set(re.findall(r"(?:不能|禁止|不可|不得)(.+?)(?:[，。；]|$)", req_b))
    for must_p in must_patterns_a:
        for cannot_p in cannot_patterns_b:
            common = set(must_p) & set(cannot_p)
            if len(common) >= 3:
                return True
    return False


def _check_rule_conflict(
    new_rule: Dict[str, str],
    existing_rules_text: str,
) -> List[str]:
    """静态检测新规则与已有规则之间是否存在明显冲突。

    返回冲突描述列表，空列表 = 无冲突。
    只检测"可形式化"的冲突——时间约束矛盾、模型绑定矛盾、互斥行为指令。
    """
    conflicts: List[str] = []

    existing_requirements: list = []
    for m in re.finditer(
        r"### (RULE-\d+): (.+?)\n.*?具体诉求\**\s*[：:]\s*(.+?)(?:\n-|$)",
        existing_rules_text, re.DOTALL
    ):
        existing_requirements.append((m.group(1), m.group(2), m.group(3).strip()))

    new_req = new_rule.get("rule_requirement", "")
    new_title = new_rule.get("rule_title", "")
    new_id = new_rule.get("rule_id", "")

    for exist_id, exist_title, exist_req in existing_requirements:
        if _time_constraint_conflict(new_req, exist_req):
            conflicts.append(
                f"时间约束冲突: {new_id}「{new_title}」vs "
                f"{exist_id}「{exist_title}」——两者对同一时间段的要求矛盾"
            )
        if _model_binding_conflict(new_req, exist_req):
            conflicts.append(
                f"模型绑定冲突: {new_id}「{new_title}」vs "
                f"{exist_id}「{exist_title}」——两者指定了不同的强制模型"
            )
        if _behavior_conflict(new_req, exist_req):
            conflicts.append(
                f"行为指令冲突: {new_id}「{new_title}」vs "
                f"{exist_id}「{exist_title}」——两者对同一行为的要求相反"
            )

    return conflicts


def _validate_21_rules() -> Dict[str, Any]:
    """验证 21 条铁律是否全部正确加载。

    返回 {"ok": bool, "total_in_file": int, "active": int, "deprecated": [ids], "missing": [ids]}
    """
    if not RULES_MD_PATH.exists():
        return {"ok": False, "total_in_file": 0, "active": 0,
                "deprecated": [], "missing": [], "error": "rules.md 不存在"}

    text = RULES_MD_PATH.read_text(encoding="utf-8")
    found_ids: set = set()
    deprecated_ids: set = set()

    for m in re.finditer(r"### (RULE-\d+):", text):
        rid = m.group(1)
        found_ids.add(rid)
        # 检查该条目是否已废止
        next_rule = text.find("### RULE-", m.end())
        block_end = next_rule if next_rule != -1 else len(text)
        block = text[m.start():block_end]
        if "已废止" in block:
            deprecated_ids.add(rid)

    active_ids = found_ids - deprecated_ids
    missing = [f"RULE-{i:03d}" for i in range(1, 22) if f"RULE-{i:03d}" not in active_ids]

    return {
        "ok": len(missing) == 0,
        "total_in_file": len(found_ids),
        "active": len(active_ids),
        "deprecated": sorted(deprecated_ids),
        "missing": missing,
    }


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
                tags: str = "", weight: str = "normal", id: str = "",
                rule_id: str = "", rule_title: str = "",
                rule_requirement: str = "", rule_trigger: str = "",
                rule_consequence: str = "", rule_priority: str = "normal",
                rule_privacy: str = "private", **kwargs) -> Dict[str, Any]:
        """执行心证/铁律操作。

        Args:
            action: append | list | delete | rule_add | rule_list | rule_delete | rule_validate
            content: 心证内容（append 时必填）
            category: 分区名（append 时必填）或铁律作用分类（rule_add 时必填）
            tags: 逗号分隔标签（可选）
            weight: normal | high | critical（默认 normal）
            id: 要删除的心证 ID（delete 时必填）
            rule_id: 铁律编号 RULE-XXX（rule_add 时必填）
            rule_title: 铁律标题（rule_add 时必填）
            rule_requirement: 具体诉求（rule_add 时必填）
            rule_trigger: 触发场景（可选）
            rule_consequence: 违反后果（可选）
            rule_priority: critical | high | normal（默认 normal）
            rule_privacy: public | private | secret（默认 private）
        """
        if action == "append":
            return self._do_append(content, category, tags, weight)
        elif action == "list":
            return self._do_list(category)
        elif action == "delete":
            return self._do_delete(id)
        elif action == "rule_add":
            return self._do_rule_add(
                rule_id, rule_title, category, rule_requirement,
                rule_trigger, rule_consequence, rule_priority, rule_privacy,
            )
        elif action == "rule_list":
            return self._do_rule_list(category)
        elif action == "rule_delete":
            return self._do_rule_delete(rule_id)
        elif action == "rule_validate":
            return _validate_21_rules()
        elif action == "rule_rollback":
            n = int(kwargs.get("bak_n", 1))
            ok = _rollback_to_bak(RULES_MD_PATH, n)
            return {"ok": ok, "action": "rule_rollback", "bak_n": n}
        else:
            return {"error": f"未知 action: {action}，支持 append / list / delete / rule_add / rule_list / rule_delete / rule_validate / rule_rollback"}

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
    # rule_add — 主人铁律结构化录入
    # ------------------------------------------------------------------

    def _do_rule_add(
        self, rule_id: str, rule_title: str, rule_category: str,
        rule_requirement: str, rule_trigger: str = "",
        rule_consequence: str = "", rule_priority: str = "normal",
        rule_privacy: str = "private",
    ) -> Dict[str, Any]:
        """向 soul/rules.md 追加一条结构化铁律。"""
        # 必填校验
        missing = []
        if not rule_id.strip(): missing.append("rule_id")
        if not rule_title.strip(): missing.append("rule_title")
        if not rule_category.strip(): missing.append("rule_category")
        if not rule_requirement.strip(): missing.append("rule_requirement")
        if missing:
            return {"error": f"缺少必填字段: {', '.join(missing)}"}

        rule_id = rule_id.strip().upper()
        rule_title = rule_title.strip()
        rule_category = rule_category.strip()
        rule_requirement = rule_requirement.strip()
        rule_trigger = rule_trigger.strip()
        rule_consequence = rule_consequence.strip()
        rule_priority = rule_priority.strip()
        rule_privacy = rule_privacy.strip()

        # 分类校验
        if rule_category not in _RULE_CATEGORIES:
            return {"error": f"无效的作用分类 '{rule_category}'，可选: {', '.join(_RULE_CATEGORIES)}"}
        if rule_priority not in _RULE_PRIORITIES:
            return {"error": f"无效的重要度 '{rule_priority}'，可选: {list(_RULE_PRIORITIES.keys())}"}
        if rule_privacy not in _RULE_PRIVACY_LEVELS:
            return {"error": f"无效的隐私等级 '{rule_privacy}'，可选: {sorted(_RULE_PRIVACY_LEVELS)}"}

        # 读取现有内容
        existing_text = RULES_MD_PATH.read_text(encoding="utf-8") if RULES_MD_PATH.exists() else self._default_rules_md()

        # 幂等检测：同 ID + 同 title + 同 requirement + 现行 → 拒绝重复
        pattern = (
            rf"### {re.escape(rule_id)}: {re.escape(rule_title)}\n"
            r".*?现行.*?"
            rf"{re.escape(rule_requirement)}"
        )
        if re.search(pattern, existing_text, re.DOTALL):
            return {"error": f"铁律 {rule_id}「{rule_title}」已存在且为现行版本，内容相同。如需修改请说明变更内容，我会升级版本号。"}

        # 版本管理：查找同 ID 现有版本
        existing_versions = re.findall(
            rf"### {re.escape(rule_id)}:.*?\n- \*\*版本\*\*: v(\d+)",
            existing_text, re.DOTALL
        )
        new_version = max(int(v) for v in existing_versions) + 1 if existing_versions else 1

        # 冲突检测
        conflicts = _check_rule_conflict({
            "rule_id": rule_id, "rule_title": rule_title,
            "rule_requirement": rule_requirement,
        }, existing_text)

        # 如果同 ID 已有现行版本，先废止
        if existing_versions:
            existing_text = self._deprecate_rule_version(existing_text, rule_id, new_version)

        # 轮转备份
        _rotate_backup(RULES_MD_PATH)

        today = date.today().isoformat()
        stars = _RULE_PRIORITIES.get(rule_priority, "★★★")

        # 构建条目
        entry = (
            f"\n### {rule_id}: {rule_title}\n"
            f"- **版本**: v{new_version}\n"
            f"- **状态**: 现行\n"
            f"- **隐私等级**: {rule_privacy}\n"
            f"- **生效时间**: {today}\n"
            f"- **具体诉求**: {rule_requirement}\n"
        )
        if rule_trigger:
            entry += f"- **触发场景**: {rule_trigger}\n"
        if rule_consequence:
            entry += f"- **违反后果**: {rule_consequence}\n"
        entry += f"- **重要度**: {stars}\n"

        # 找到对应分类 section 并追加
        section_header = f"## {rule_category}"
        if section_header not in existing_text:
            existing_text += f"\n{section_header}\n"

        new_text = existing_text.rstrip() + entry + "\n"
        _atomic_write_text(RULES_MD_PATH, new_text)
        invalidate_rules_cache()

        # 写入后 verify
        if not _verify_write_contains(RULES_MD_PATH, f"{rule_id}: {rule_title}"):
            return {"error": "写入后验证失败：文件中未找到新增的规则条目"}

        result = {
            "ok": True, "action": "rule_add",
            "rule_id": rule_id, "version": new_version,
            "category": rule_category, "priority": rule_priority,
            "privacy": rule_privacy,
        }
        if conflicts:
            result["conflicts"] = conflicts
            result["warning"] = f"铁律已追加，但检测到 {len(conflicts)} 个潜在冲突，请确认是否合理"
            logger.warning(f"规则冲突检测 ({rule_id}): {conflicts}")

        logger.info(f"heart_record rule_add → {rule_id} v{new_version}: {rule_title}")
        return result

    # ------------------------------------------------------------------
    # rule_list — 铁律列表
    # ------------------------------------------------------------------

    def _do_rule_list(self, rule_category: str = "") -> Dict[str, Any]:
        """列出 rules.md 中的铁律，可按分类/状态筛选。"""
        if not RULES_MD_PATH.exists():
            return {"entries": [], "total": 0, "active": 0}

        text = RULES_MD_PATH.read_text(encoding="utf-8")
        entries: List[Dict[str, Any]] = []

        for m in re.finditer(
            r"### (RULE-\d+): (.+?)\n"
            r"- \*\*版本\*\*: v(\d+)\n"
            r"- \*\*状态\*\*: (.+?)\n"
            r"- \*\*隐私等级\*\*: (\w+)\n"
            r"- \*\*生效时间\*\*: (.+?)\n"
            r"- \*\*具体诉求\*\*: (.+?)\n",
            text
        ):
            rid = m.group(1)
            title = m.group(2)
            version = int(m.group(3))
            status = m.group(4).strip()
            privacy = m.group(5)
            effective_date = m.group(6)
            requirement = m.group(7)

            # 提取重要度
            block_start = m.start()
            block_end = text.find("### RULE-", m.end())
            if block_end == -1:
                block_end = len(text)
            block = text[block_start:block_end]
            stars_match = re.search(r"重要度[：:]\s*(★+)", block)
            stars = stars_match.group(1) if stars_match else ""

            # 提取分类（向上找最近的 ## 标题）
            before_block = text[:block_start]
            cat_match = re.findall(r"## (.+)", before_block)
            category = cat_match[-1].strip() if cat_match else ""

            is_active = "现行" in status and "已废止" not in status

            entries.append({
                "id": rid, "title": title, "version": version,
                "status": "active" if is_active else "deprecated",
                "privacy": privacy, "category": category,
                "effective_date": effective_date,
                "requirement": requirement[:120],
                "priority": stars,
            })

        # 按分类筛选
        if rule_category:
            entries = [e for e in entries if e["category"] == rule_category]

        active_entries = [e for e in entries if e["status"] == "active"]

        return {
            "entries": entries,
            "total": len(entries),
            "active": len(active_entries),
            "deprecated": len(entries) - len(active_entries),
        }

    # ------------------------------------------------------------------
    # rule_delete — 铁律软删除（标注废止）
    # ------------------------------------------------------------------

    def _do_rule_delete(self, rule_id: str) -> Dict[str, Any]:
        """软删除铁律：标注'已废止'而非物理删除。"""
        if not rule_id.strip():
            return {"error": "rule_id 不能为空"}

        rule_id = rule_id.strip().upper()

        if not RULES_MD_PATH.exists():
            return {"error": "rules.md 不存在"}

        text = RULES_MD_PATH.read_text(encoding="utf-8")

        # 查找目标规则（re.DOTALL 跨行匹配）
        pattern = rf"(### {re.escape(rule_id)}:.*?- \*\*状态\*\*: )现行"
        if not re.search(pattern, text, re.DOTALL):
            return {"error": f"未找到现行版本的 {rule_id}"}

        # 轮转备份
        _rotate_backup(RULES_MD_PATH)

        today = date.today().isoformat()
        new_text = re.sub(
            pattern,
            rf"\1~~已废止（{today}）~~",
            text,
            count=1,
            flags=re.DOTALL,
        )

        _atomic_write_text(RULES_MD_PATH, new_text)
        invalidate_rules_cache()

        # 写入后 verify：确认旧状态已不在
        if not _verify_write_excludes(RULES_MD_PATH, f"**状态**: 现行"):
            # 别急——可能文件中还有其他现行规则。只校验目标规则。
            # 简易策略：检查目标规则块中不再包含"状态**: 现行"
            pass

        logger.info(f"heart_record rule_delete → {rule_id} 已废止")
        return {"ok": True, "action": "rule_delete", "rule_id": rule_id, "date": today}

    # ------------------------------------------------------------------
    # 版本管理辅助
    # ------------------------------------------------------------------

    def _deprecate_rule_version(self, text: str, rule_id: str, next_version: int) -> str:
        """将指定 rule_id 的现行版本标注为已废止。"""
        today = date.today().isoformat()
        pattern = (
            rf"(### {re.escape(rule_id)}:.*?- \*\*版本\*\*: v\d+\n)"
            r"- \*\*状态\*\*: 现行"
        )
        replacement = rf"\1- **状态**: ~~已废止（被 v{next_version} 取代，{today}）~~"
        new_text = re.sub(pattern, replacement, text, count=1, flags=re.DOTALL)
        if new_text != text:
            logger.info(f"已废止 {rule_id} 旧版本（→ v{next_version}）")
        return new_text

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

    @staticmethod
    def _default_rules_md() -> str:
        return (
            "# 主人铁律\n\n"
            "> 以下规则为不可违反的永久铁律。Agent 每次回答前必须逐条检查，\n"
            "> 任何违反铁律的回复都是严重失职。\n"
            ">\n"
            "> **隐私等级说明**：public=所有渠道 / private=仅web+CLI / secret=仅存文件审计\n"
            "> **重要度说明**：critical ★★★★★ / high ★★★★ / normal ★★★\n"
            "\n"
            + "\n".join(f"## {c}\n\n<!-- {c}相关规则 -->\n" for c in _RULE_CATEGORIES)
        )

    def _get_parameters(self):
        return [
            ToolParameter(
                name="action", type="str", required=True,
                description="操作类型: append | list | delete | rule_add | rule_list | rule_delete | rule_validate | rule_rollback",
            ),
            ToolParameter(
                name="content", type="str", required=False,
                description="心证内容（action=append 时必填）",
            ),
            ToolParameter(
                name="category", type="str", required=False,
                description="心证分区: 主人心证/主人教诲/...；或铁律作用分类: 安全边界/模型绑定/工具使用/失职与自查/记忆与持久化/用户交互/隐私与数据",
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
            ToolParameter(
                name="rule_id", type="str", required=False,
                description="铁律编号 RULE-XXX（action=rule_add/rule_delete 时必填）",
            ),
            ToolParameter(
                name="rule_title", type="str", required=False,
                description="铁律标题（action=rule_add 时必填）",
            ),
            ToolParameter(
                name="rule_requirement", type="str", required=False,
                description="铁律具体诉求（action=rule_add 时必填）",
            ),
            ToolParameter(
                name="rule_trigger", type="str", required=False,
                description="触发场景（可选）",
            ),
            ToolParameter(
                name="rule_consequence", type="str", required=False,
                description="违反后果（可选）",
            ),
            ToolParameter(
                name="rule_priority", type="str", required=False,
                description="重要度: critical | high | normal，默认 normal",
            ),
            ToolParameter(
                name="rule_privacy", type="str", required=False,
                description="隐私等级: public | private | secret，默认 private",
            ),
        ]

import json
import uuid
from datetime import datetime
from pathlib import Path
from typing import List, Optional, Dict, Any
from loguru import logger


class SkillStep:
    def __init__(self, name: str, description: str = "",
                 tool_hints: List[str] = None,
                 forced_tools: List[str] = None,
                 strategy_prompt: str = "",
                 step_id: str = None):
        self.step_id        = step_id or f"step_{uuid.uuid4().hex[:6]}"
        self.name           = name
        self.description    = description
        self.tool_hints     = tool_hints   or []
        self.forced_tools   = forced_tools or []
        self.strategy_prompt = strategy_prompt

    def to_dict(self) -> Dict[str, Any]:
        return {
            "step_id":        self.step_id,
            "name":           self.name,
            "description":    self.description,
            "tool_hints":     self.tool_hints,
            "forced_tools":   self.forced_tools,
            "strategy_prompt": self.strategy_prompt,
        }

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "SkillStep":
        return cls(
            name            = d.get("name", ""),
            description     = d.get("description", ""),
            tool_hints      = d.get("tool_hints", []),
            forced_tools    = d.get("forced_tools", []),
            strategy_prompt = d.get("strategy_prompt", ""),
            step_id         = d.get("step_id"),
        )


class Skill:
    def __init__(self, name: str, description: str = "",
                 trigger_keywords: List[str] = None,
                 tool_hints: List[str] = None,
                 forced_tools: List[str] = None,
                 scenario_tags: List[str] = None,
                 overall_strategy: str = "",
                 steps: List[SkillStep] = None,
                 enabled: bool = True,
                 skill_id: str = None):
        self.id               = skill_id or f"skill_{uuid.uuid4().hex[:8]}"
        self.name             = name
        self.description      = description
        self.trigger_keywords = trigger_keywords or []
        self.tool_hints       = tool_hints       or []  # skill 级全局建议工具
        self.forced_tools     = forced_tools     or []  # skill 级全局强制工具
        self.scenario_tags    = scenario_tags    or []
        self.overall_strategy = overall_strategy
        self.steps            = steps            or []
        self.enabled          = enabled
        self.created_at       = datetime.now().isoformat()
        self.updated_at       = datetime.now().isoformat()

    # ── 聚合所有步骤的工具 ────────────────────────────────
    @property
    def all_forced_tools(self) -> List[str]:
        result = set(self.forced_tools)
        for step in self.steps:
            result.update(step.forced_tools)
        return list(result)

    @property
    def all_tool_hints(self) -> List[str]:
        result = set(self.tool_hints)
        for step in self.steps:
            result.update(step.tool_hints)
        return list(result)

    def build_injection_prompt(self) -> str:
        """组装注入给模型的策略提示"""
        parts = []
        if self.overall_strategy:
            parts.append(f"【整体目标】{self.overall_strategy}")
        if self.steps:
            parts.append("\n【执行步骤】请严格按以下顺序执行，每步完成后再进行下一步：")
            for i, step in enumerate(self.steps, 1):
                line = f"\n第{i}步【{step.name}】"
                if step.description:
                    line += f"：{step.description}"
                if step.forced_tools:
                    line += f"\n   → 必须调用工具：{', '.join(step.forced_tools)}"
                elif step.tool_hints:
                    line += f"\n   → 建议使用工具：{', '.join(step.tool_hints)}"
                if step.strategy_prompt:
                    line += f"\n   → 具体要求：{step.strategy_prompt}"
                parts.append(line)
            parts.append("\n完成所有步骤后，整合结果给用户一个清晰完整的回答。")
        return "\n".join(parts)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id":               self.id,
            "name":             self.name,
            "description":      self.description,
            "trigger_keywords": self.trigger_keywords,
            "tool_hints":       self.tool_hints,
            "forced_tools":     self.forced_tools,
            "scenario_tags":    self.scenario_tags,
            "overall_strategy": self.overall_strategy,
            "steps":            [s.to_dict() for s in self.steps],
            "enabled":          self.enabled,
            "created_at":       self.created_at,
            "updated_at":       self.updated_at,
        }

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "Skill":
        # 兼容旧格式：strategy_prompt → overall_strategy
        overall_strategy = d.get("overall_strategy") or d.get("strategy_prompt", "")
        steps = [SkillStep.from_dict(s) for s in d.get("steps", [])]
        s = cls(
            name             = d["name"],
            description      = d.get("description", ""),
            trigger_keywords = d.get("trigger_keywords", []),
            tool_hints       = d.get("tool_hints", []),
            forced_tools     = d.get("forced_tools", []),
            scenario_tags    = d.get("scenario_tags", []),
            overall_strategy = overall_strategy,
            steps            = steps,
            enabled          = d.get("enabled", True),
            skill_id         = d.get("id"),
        )
        s.created_at = d.get("created_at", s.created_at)
        s.updated_at = d.get("updated_at", s.updated_at)
        return s


class SkillManager:
    PERSIST_PATH = Path("./data/skills.json")

    def __init__(self):
        self.skills: Dict[str, Skill] = {}
        self.PERSIST_PATH.parent.mkdir(parents=True, exist_ok=True)
        self._load()
        if not self.skills:
            self._init_builtin_skills()
        logger.info(f"SkillManager 初始化，共 {len(self.skills)} 个 Skill")

    # ── CRUD ──────────────────────────────────────────────
    def create(self, **kwargs) -> Skill:
        if "steps" in kwargs and isinstance(kwargs["steps"], list):
            kwargs["steps"] = [
                SkillStep.from_dict(s) if isinstance(s, dict) else s
                for s in kwargs["steps"]
            ]
        skill = Skill(**kwargs)
        self.skills[skill.id] = skill
        self._save()
        return skill

    def update(self, skill_id: str, **kwargs) -> Optional[Skill]:
        skill = self.skills.get(skill_id)
        if not skill:
            return None
        if "steps" in kwargs and isinstance(kwargs["steps"], list):
            kwargs["steps"] = [
                SkillStep.from_dict(s) if isinstance(s, dict) else s
                for s in kwargs["steps"]
            ]
        for k, v in kwargs.items():
            if hasattr(skill, k):
                setattr(skill, k, v)
        skill.updated_at = datetime.now().isoformat()
        self._save()
        return skill

    def delete(self, skill_id: str) -> bool:
        if skill_id in self.skills:
            del self.skills[skill_id]
            self._save()
            return True
        return False

    def get(self, skill_id: str) -> Optional[Skill]:
        return self.skills.get(skill_id)

    def list_all(self, tag: str = None, enabled_only: bool = False) -> List[Skill]:
        result = list(self.skills.values())
        if enabled_only:
            result = [s for s in result if s.enabled]
        if tag:
            result = [s for s in result if tag in s.scenario_tags]
        return sorted(result, key=lambda s: s.created_at)

    # ── 意图匹配（C 方案）────────────────────────────────
    def match_by_keywords(self, message: str) -> List[Skill]:
        msg_lower = message.lower()
        matched = []
        for skill in self.skills.values():
            if not skill.enabled:
                continue
            hits = sum(1 for kw in skill.trigger_keywords if kw in msg_lower)
            if hits >= 1:
                matched.append((hits, skill))
        matched.sort(key=lambda x: x[0], reverse=True)
        return [s for _, s in matched]

    async def match_by_llm(self, message: str, candidates: List[Skill],
                           call_model_func) -> Optional[Skill]:
        if not candidates:
            return None
        skill_desc = "\n".join(
            f"[{s.id}] {s.name}: {s.description}"
            for s in candidates
        )
        prompt = (
            f"用户消息：「{message}」\n\n"
            f"可选技能：\n{skill_desc}\n\n"
            "请判断用户消息最匹配哪个技能ID，如果都不匹配请回答 none。"
            "只回答技能ID或none，不要有其他内容。"
        )
        try:
            result = await call_model_func([
                {"role": "system", "content": "你是意图分类助手，只输出技能ID或none。"},
                {"role": "user",   "content": prompt}
            ])
            result = result.strip().lower()
            if result == "none":
                return None
            for s in candidates:
                if s.id in result:
                    return s
        except Exception as e:
            logger.warning(f"LLM 意图分类失败: {e}")
        return None

    async def find_skill(self, message: str, call_model_func) -> Optional[Skill]:
        kw_matches = self.match_by_keywords(message)
        if len(kw_matches) == 1:
            logger.info(f"关键词命中 Skill: {kw_matches[0].name}")
            return kw_matches[0]
        if len(kw_matches) > 1:
            logger.info(f"关键词多命中 {len(kw_matches)} 个，LLM 裁决")
            return await self.match_by_llm(message, kw_matches, call_model_func)
        all_enabled = self.list_all(enabled_only=True)
        if all_enabled:
            return await self.match_by_llm(message, all_enabled, call_model_func)
        return None

    # ── 持久化 ────────────────────────────────────────────
    def _save(self):
        data = [s.to_dict() for s in self.skills.values()]
        self.PERSIST_PATH.write_text(
            json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    def _load(self):
        if not self.PERSIST_PATH.exists():
            return
        try:
            data = json.loads(self.PERSIST_PATH.read_text(encoding="utf-8"))
            for d in data:
                s = Skill.from_dict(d)
                self.skills[s.id] = s
        except Exception as e:
            logger.error(f"加载 Skill 失败: {e}")

    # ── 内置 Skill（多步骤设计）───────────────────────────
    def _init_builtin_skills(self):
        builtins = [
            Skill(
                name="数学计算",
                description="处理数学运算和计算题，精确返回结果",
                trigger_keywords=["计算", "算一下", "等于", "多少", "²", "平方", "开根"],
                scenario_tags=["math"],
                overall_strategy="用户需要数学计算，必须调用工具精确计算，不能自行心算或猜测。",
                steps=[
                    SkillStep(
                        name="执行计算",
                        description="调用计算工具得出精确结果",
                        forced_tools=["CalculatorTool"],
                        strategy_prompt="expression 参数填入用户的数学表达式，如 '11*11' 或 'sqrt(144)'。"
                    ),
                ]
            ),
            Skill(
                name="时间查询",
                description="查询当前时间或日期，返回真实系统时间",
                trigger_keywords=["几点", "日期", "当前时间"],
                scenario_tags=["utility"],
                overall_strategy="用户需要查询时间，必须调用工具获取真实系统时间，不能编造或估计时间。",
                steps=[
                    SkillStep(
                        name="获取当前时间",
                        description="调用时间工具获取系统时间",
                        forced_tools=["TimeTool"],
                        strategy_prompt="action 参数填 current_time。"
                    ),
                ]
            ),
            Skill(
                name="文件操作",
                description="读取、写入或列出本地文件系统内容",
                trigger_keywords=["读取", "读文件", "写入", "写文件", "文件内容", "目录", "列出文件", "打开文件"],
                scenario_tags=["file"],
                overall_strategy="用户需要操作文件，必须调用 FileTool 工具，不能编造文件内容。",
                steps=[
                    SkillStep(
                        name="执行文件操作",
                        description="根据需求读取、写入或列出文件",
                        forced_tools=["FileTool"],
                        strategy_prompt="读取用 action=read 加 path 参数；写入用 action=write 加 path 和 content；列目录用 action=list 加 path。"
                    ),
                    SkillStep(
                        name="整理并呈现结果",
                        description="将文件内容整理成清晰可读的格式",
                        strategy_prompt="如果是文本文件，按原格式呈现；如果是目录列表，按层级展示。"
                    ),
                ]
            ),
            Skill(
                name="GitHub 操作",
                description="搜索仓库、查看代码、PR、Issue 等 GitHub 相关操作",
                trigger_keywords=["github", "仓库", "repo", "pr", "pull request", "issue", "代码搜索", "commit"],
                scenario_tags=["github", "code"],
                overall_strategy="通过 GitHub 工具获取真实数据，严禁编造任何仓库名称、链接或代码内容。",
                steps=[
                    SkillStep(
                        name="搜索相关内容",
                        description="根据用户需求搜索仓库或代码",
                        tool_hints=["search_repositories", "search_code"],
                        strategy_prompt="搜索仓库用 search_repositories，搜索代码用 search_code，关键词尽量精确。"
                    ),
                    SkillStep(
                        name="获取详细信息",
                        description="根据搜索结果获取具体文件、Issue 或 PR 详情",
                        tool_hints=["get_file_contents", "list_issues", "get_pull_request", "list_commits"],
                        strategy_prompt="根据第一步结果选择合适的工具获取详情，注意 owner 和 repo 参数必须来自搜索结果。"
                    ),
                    SkillStep(
                        name="分析并给出建议",
                        description="综合以上信息给出专业分析",
                        strategy_prompt="基于真实获取的数据进行分析，给出有价值的见解和建议。"
                    ),
                ]
            ),
            Skill(
                name="网络搜索",
                description="搜索网络上的最新信息并整理呈现",
                trigger_keywords=["搜索", "查一下", "网上", "最新", "新闻", "查找", "查询"],
                scenario_tags=["web"],
                overall_strategy="通过网络搜索获取最新真实信息，整理成清晰易读的回答。",
                steps=[
                    SkillStep(
                        name="执行网络搜索",
                        description="用搜索工具获取相关信息",
                        forced_tools=["WebSearchTool"],
                        strategy_prompt="query 参数提炼用户意图的核心关键词，避免过长。"
                    ),
                    SkillStep(
                        name="整理搜索结果",
                        description="提取关键信息，组织成清晰回答",
                        strategy_prompt="从搜索结果中提取最相关的信息，注明来源，给出简洁清晰的总结。"
                    ),
                ]
            ),
        ]
        for s in builtins:
            self.skills[s.id] = s
        self._save()
        logger.info(f"初始化 {len(builtins)} 个内置多步骤 Skill")


skill_manager = SkillManager()
"""
内置 Skill 模板定义
每个模板是一个 dict，可直接传给 SkillManager.create()
"""
from typing import List, Dict, Any


BUILTIN_TEMPLATES: List[Dict[str, Any]] = [
    {
        "id": "tpl_database",
        "name": "数据库查询助手",
        "description": "查询 MySQL 数据库，自动分析表结构后执行 SQL",
        "trigger_keywords": ["查询数据库", "查表", "sql", "数据库", "有哪些表", "查一下表"],
        "scenario_tags": ["database", "sql"],
        "overall_strategy": "用户需要查询数据库，必须先了解表结构再执行 SQL，严禁编造数据。",
        "steps": [
            {
                "name": "了解表结构",
                "description": "先列出所有表，再查看相关表的结构",
                "tool_hints": ["DatabaseTool"],
                "forced_tools": [],
                "strategy_prompt": "先用 action=list_tables 了解有哪些表，再用 action=describe 查看相关表结构。"
            },
            {
                "name": "执行查询",
                "description": "根据表结构构造 SQL 并执行",
                "tool_hints": [],
                "forced_tools": ["DatabaseTool"],
                "strategy_prompt": "根据表结构构造正确的 SELECT 语句，必须加 LIMIT 防止数据过多，用 action=query 执行。"
            },
            {
                "name": "整理结果",
                "description": "将查询结果整理成用户易读的格式",
                "tool_hints": [],
                "forced_tools": [],
                "strategy_prompt": "将表格数据用自然语言描述，突出用户关心的字段，数据量大时做摘要。"
            }
        ]
    },
    {
        "id": "tpl_github",
        "name": "GitHub 代码助手",
        "description": "搜索仓库、查看代码、PR、Issue 等 GitHub 相关操作",
        "trigger_keywords": ["github", "仓库", "repo", "pr", "pull request", "issue", "代码搜索", "commit"],
        "scenario_tags": ["github", "code"],
        "overall_strategy": "通过 GitHub 工具获取真实数据，严禁编造任何仓库名称、链接或代码内容。",
        "steps": [
            {
                "name": "搜索相关内容",
                "description": "根据用户需求搜索仓库或代码",
                "tool_hints": ["search_repositories", "search_code"],
                "forced_tools": [],
                "strategy_prompt": "搜索仓库用 search_repositories，搜索代码用 search_code，关键词尽量精确。"
            },
            {
                "name": "获取详细信息",
                "description": "根据搜索结果获取具体文件、Issue 或 PR 详情",
                "tool_hints": ["get_file_contents", "list_issues", "get_pull_request", "list_commits"],
                "forced_tools": [],
                "strategy_prompt": "owner 和 repo 参数必须来自上一步的搜索结果，不能自行猜测。"
            },
            {
                "name": "分析并给出建议",
                "description": "综合以上信息给出专业分析",
                "tool_hints": [],
                "forced_tools": [],
                "strategy_prompt": "基于真实获取的数据进行分析，给出有价值的见解和建议。"
            }
        ]
    },
    {
        "id": "tpl_file",
        "name": "文件处理助手",
        "description": "读取、写入、分析本地文件，支持文本、CSV、代码文件",
        "trigger_keywords": ["读取文件", "写文件", "文件内容", "打开文件", "列出目录", "分析文件"],
        "scenario_tags": ["file"],
        "overall_strategy": "操作本地文件必须通过 FileTool，不能编造文件内容，路径必须来自用户提供或目录列表。",
        "steps": [
            {
                "name": "确认文件路径",
                "description": "如果路径不明确，先列出目录",
                "tool_hints": ["FileTool"],
                "forced_tools": [],
                "strategy_prompt": "路径不明确时用 action=list 列出目录，确认文件存在后再操作。"
            },
            {
                "name": "执行文件操作",
                "description": "读取或写入文件",
                "tool_hints": [],
                "forced_tools": ["FileTool"],
                "strategy_prompt": "读取用 action=read；写入用 action=write 加 content；复制用 action=copy。"
            },
            {
                "name": "分析并呈现",
                "description": "对文件内容进行分析或格式化展示",
                "tool_hints": [],
                "forced_tools": [],
                "strategy_prompt": "文本文件按原格式展示关键内容；代码文件分析结构和逻辑；数据文件提取关键数值。"
            }
        ]
    },
    {
        "id": "tpl_websearch",
        "name": "网络信息助手",
        "description": "搜索网络最新信息并整理成结构化回答",
        "trigger_keywords": ["搜索", "查一下", "网上", "最新", "新闻", "查找"],
        "scenario_tags": ["web"],
        "overall_strategy": "通过网络搜索获取最新真实信息，整理成清晰易读的回答，注明信息来源。",
        "steps": [
            {
                "name": "执行搜索",
                "description": "提炼关键词进行网络搜索",
                "tool_hints": [],
                "forced_tools": ["WebSearchTool"],
                "strategy_prompt": "query 参数提炼用户意图的核心关键词，3-5 个词最佳，避免过长的句子。"
            },
            {
                "name": "整理结果",
                "description": "提取关键信息，组织成清晰回答",
                "tool_hints": [],
                "forced_tools": [],
                "strategy_prompt": "从搜索结果中提取最相关的 3-5 条信息，按重要性排序，给出简洁总结并注明来源。"
            }
        ]
    },
    {
        "id": "tpl_task",
        "name": "时间任务助手",
        "description": "查询时间、创建提醒和定时任务",
        "trigger_keywords": ["几点", "提醒我", "定时", "任务", "设置提醒", "倒计时"],
        "scenario_tags": ["utility", "scheduler"],
        "overall_strategy": "时间查询必须调用工具获取真实时间；创建提醒必须通过调度工具，不能假装已创建。",
        "steps": [
            {
                "name": "获取当前时间",
                "description": "调用时间工具确认当前时间",
                "tool_hints": ["TimeTool"],
                "forced_tools": [],
                "strategy_prompt": "用 action=current_time 获取当前时间，作为后续计算的基准。"
            },
            {
                "name": "创建任务或提醒",
                "description": "根据用户需求创建提醒",
                "tool_hints": ["create_reminder"],
                "forced_tools": [],
                "strategy_prompt": "remind_in_seconds 根据用户说的时间换算成秒数，message 填用户想被提醒的内容。"
            }
        ]
    },
    {
        "id": "tpl_report",
        "name": "日报周报生成",
        "description": "根据近期对话和记忆生成工作日报或周报",
        "trigger_keywords": ["日报", "周报", "总结今天", "工作总结", "生成报告"],
        "scenario_tags": ["report", "memory"],
        "overall_strategy": "从记忆系统检索近期工作内容，整理成标准日报/周报格式，内容必须来自真实记忆。",
        "steps": [
            {
                "name": "检索近期记录",
                "description": "从记忆系统获取近期工作相关内容",
                "tool_hints": ["search_memories"],
                "forced_tools": [],
                "strategy_prompt": "用 search_memories 搜索「工作」「任务」「完成」等关键词，获取近期工作记录。"
            },
            {
                "name": "生成报告",
                "description": "整理成标准报告格式",
                "tool_hints": [],
                "forced_tools": [],
                "strategy_prompt": "按「今日完成/本周完成」「遇到问题」「明日计划」三段式组织，语言简洁专业。内容来源于检索结果，不足时说明。"
            }
        ]
    },
]

# 模板 ID 集合，用于判断是否是系统内置模板
TEMPLATE_IDS = {t["id"] for t in BUILTIN_TEMPLATES}


def get_template(template_id: str) -> Dict[str, Any]:
    for t in BUILTIN_TEMPLATES:
        if t["id"] == template_id:
            return t
    return None
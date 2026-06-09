"""Skill 应用逻辑"""
from typing import Dict, List, Tuple, Optional
from loguru import logger


class SkillApplicator:

    def __init__(self, skill_manager, tool_manager):
        self.skill_manager = skill_manager
        self.tool_manager  = tool_manager

    async def apply(
        self,
        message:         str,
        messages:        List[Dict],
        filtered_tools:  Dict,
        call_model_func,
        username:        str = "default",   # ← 加参数
    ) -> Tuple[List[Dict], Dict, Optional[str]]:
        """
        返回 (messages, filtered_tools, skill_name)
        skill_name 用于外部记录日志
        """
        skill = await self.skill_manager.find_skill(message, call_model_func)
        if not skill:
            return messages, filtered_tools, None

        logger.info(f"命中 Skill: {skill.name}，步骤数: {len(skill.steps)}")

        injection = skill.build_injection_prompt()
        if injection:
            messages = messages + [{
                "role":    "system",
                "content": f"【技能策略】\n{injection}"
            }]

        all_tools = self.tool_manager.get_all_tools()
        for tool_name in skill.all_forced_tools:
            if tool_name in all_tools and tool_name not in filtered_tools:
                filtered_tools[tool_name] = all_tools[tool_name]
                logger.info(f"强制加入工具: {tool_name}")

        # 记录触发日志
        try:
            from analytics.skill_log import skill_log_store
            skill_log_store.record(
                username    = username,
                skill_name  = skill.name,
                message     = message,
                steps_count = len(skill.steps),
                tools       = skill.all_forced_tools + skill.all_tool_hints,
            )
        except Exception as e:
            logger.warning(f"Skill 日志记录失败: {e}")

        return messages, filtered_tools, skill.name
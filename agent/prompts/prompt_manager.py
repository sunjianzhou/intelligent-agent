"""Prompt 模板管理器：从 prompts/ 目录加载 YAML 模板，按模型名匹配。"""
import re
from pathlib import Path
from typing import Dict, Optional
from loguru import logger

try:
    import yaml
    _HAS_YAML = True
except ImportError:
    _HAS_YAML = False

_PROMPTS_DIR = Path(__file__).parent


class PromptManager:
    """按模型名选择并渲染 system prompt。

    查找优先级：
    1. prompts/ 目录下 model_pattern 包含在模型名中的 YAML 文件
    2. 降级到 system_default.yaml
    3. 再降级到内嵌默认模板（当 yaml 不可用时）
    """

    _FALLBACK = (
        "你是一个有帮助的AI助手，请用中文回答。"
        "调用工具时使用：<tool_call>{\"tool\": \"工具名\", \"args\": {}}</tool_call>"
    )

    def __init__(self):
        self._templates: Dict[str, dict] = {}
        self._load_all()

    def _load_all(self) -> None:
        if not _HAS_YAML:
            logger.warning("pyyaml 未安装，Prompt 模板功能降级为内嵌默认文本")
            return
        for path in sorted(_PROMPTS_DIR.glob("system_*.yaml")):
            try:
                with open(path, encoding="utf-8") as f:
                    data = yaml.safe_load(f)
                pattern = data.get("model_pattern", "default")
                self._templates[pattern] = data
                logger.debug(f"加载 Prompt 模板: {path.name} (pattern={pattern}, v{data.get('version')})")
            except Exception as e:
                logger.warning(f"加载 Prompt 模板失败 {path.name}: {e}")

    def get(self, model_name: str = "", persona_content: Optional[str] = None) -> str:
        """根据模型名返回匹配的 system prompt 文本。

        persona_content: 若提供，则使用角色内容 + 模型 overlay 组合；
                         否则使用模板的完整 template 字段。
        """
        model_lower = (model_name or "").lower()

        matched_data = None
        # 按 model_pattern 匹配（跳过 default）
        for pattern, data in self._templates.items():
            if pattern != "default" and pattern in model_lower:
                matched_data = data
                break

        if matched_data is None:
            matched_data = self._templates.get("default")

        if persona_content and matched_data:
            # persona_template 优先：将角色内容嵌入模板（适用于触发词顺序敏感的模型，如 dolphin）
            persona_template = matched_data.get("persona_template", "").strip()
            if persona_template and "{persona_content}" in persona_template:
                return persona_template.replace("{persona_content}", persona_content.strip())
            # 降级：overlay 追加到角色内容之后
            overlay = matched_data.get("overlay", "").strip()
            if overlay:
                return persona_content.strip() + "\n\n" + overlay
            return persona_content.strip()

        if matched_data:
            return matched_data.get("template", self._FALLBACK).strip()

        return self._FALLBACK

    def get_overlay(self, model_name: str = "") -> str:
        """只返回 overlay 字段，供 SystemPromptBuilder 作为独立 tool_overlay 注入。"""
        model_lower = (model_name or "").lower()
        matched_data = None
        for pattern, data in self._templates.items():
            if pattern != "default" and pattern in model_lower:
                matched_data = data
                break
        if matched_data is None:
            matched_data = self._templates.get("default")
        if matched_data:
            return matched_data.get("overlay", "").strip()
        return ""

    def reload(self) -> None:
        """热重载所有模板（无需重启服务）。"""
        self._templates.clear()
        self._load_all()
        logger.info("Prompt 模板已重载")


# 全局单例
prompt_manager = PromptManager()

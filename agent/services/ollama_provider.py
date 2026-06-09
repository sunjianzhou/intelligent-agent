"""Ollama LLM Provider 实现"""
import json
import time
import requests
from typing import List, Optional, Dict, Any

from config.settings import settings
from services.base_provider import BaseLLMProvider, ChatMessage, LLMConfig, LLMResponse
from loguru import logger


class OllamaProvider(BaseLLMProvider):

    def __init__(self, base_url: str = None):
        self._base_url   = base_url or settings.ollama_base_url
        self._model      = settings.ollama_model
        self._default_config = LLMConfig(
            temperature   = settings.ollama_temperature,
            max_tokens    = settings.ollama_max_tokens,
            top_p         = settings.ollama_top_p,
            top_k         = settings.ollama_top_k,
            repeat_penalty= settings.ollama_repeat_penalty,
            num_ctx       = settings.ollama_num_ctx,
            num_gpu       = getattr(settings, 'ollama_num_gpu', -1),
        )
        self._models_cache: List[str] = []
        self._refresh_models()
        self._model = self._find_best_match(self._model)
        logger.info(f"OllamaProvider 初始化完成，当前模型: {self._model}")

    # ── 抽象属性实现 ──────────────────────────────────────
    @property
    def provider_name(self) -> str:
        return 'ollama'

    @property
    def current_model(self) -> str:
        return self._model

    @current_model.setter
    def current_model(self, model: str):
        self._model = model

    def available_models(self) -> List[str]:
        return self._models_cache

    def build_tool_schemas_from(self, tools: dict) -> List[Dict[str, Any]]:
        """从指定工具字典构建 schema"""
        schemas = []
        for name, tool in tools.items():
            properties = {}
            required = []
            for p in tool.parameters:
                prop_type = {
                    "str": "string", "int": "integer", "float": "number",
                    "bool": "boolean", "list": "array", "dict": "object",
                }.get(p.type, "string")
                properties[p.name] = {
                    "type": prop_type, "description": p.description,
                }
                if p.default is not None:
                    properties[p.name]["default"] = p.default
                if p.required:
                    required.append(p.name)
            schemas.append({
                "type": "function",
                "function": {
                    "name": name,
                    "description": tool.description,
                    "parameters": {
                        "type": "object",
                        "properties": properties,
                        "required": required,
                    }
                }
            })
        return schemas

    def check_connection(self) -> bool:
        try:
            r = requests.get(f"{self._base_url}/api/tags", timeout=settings.ollama_timeout)
            return r.status_code == 200
        except Exception:
            return False

    # ── 核心 chat 实现 ────────────────────────────────────
    def chat(
        self,
        messages: List[ChatMessage],
        config: Optional[LLMConfig] = None
    ) -> LLMResponse:
        cfg = config or self._default_config
        payload = {
            "model": self._model,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "stream": False,
            "keep_alive": settings.ollama_keep_alive,
            "options": {
                "temperature":    cfg.temperature,
                "top_p":          cfg.top_p,
                "top_k":          cfg.top_k,
                "num_predict":    cfg.max_tokens,
                "repeat_penalty": cfg.repeat_penalty,
                "num_ctx":        cfg.num_ctx,
                **({"num_gpu": cfg.num_gpu} if cfg.num_gpu >= 0 else {}),
            }
        }
        try:
            r = requests.post(
                f"{self._base_url}/api/chat",
                json=payload,
                timeout=settings.ollama_timeout
            )
            if r.status_code == 200:
                data = r.json()
                content = data.get('message', {}).get('content', '').strip()
                return LLMResponse(
                    content       = content,
                    model         = self._model,
                    prompt_tokens = data.get('prompt_eval_count', 0),
                    output_tokens = data.get('eval_count', 0),
                    success       = True
                )
            else:
                try:
                    err_detail = r.json().get('error', r.text[:200])
                except Exception:
                    err_detail = r.text[:200]

                logger.error(f"Ollama 返回 {r.status_code}: {err_detail}")
                return LLMResponse(
                    content=f"模型调用失败（{r.status_code}）：{err_detail}",
                    model=self._model,
                    success=False,
                    error=err_detail
                )
        except requests.exceptions.Timeout:
            return LLMResponse(content='请求超时', model=self._model, success=False, error='timeout')
        except Exception as e:
            logger.error(f"Ollama chat 异常: {e}")
            return LLMResponse(content=str(e), model=self._model, success=False, error=str(e))

    # ── 模型管理 ──────────────────────────────────────────
    def switch_model(self, model: str) -> bool:
        if model not in self._models_cache:
            return False
        self._model = model
        logger.info(f"模型切换为: {model}")
        return True

    def _refresh_models(self):
        try:
            r = requests.get(f"{self._base_url}/api/tags", timeout=settings.ollama_timeout)
            if r.status_code == 200:
                self._models_cache = [
                    m.get('name', '') for m in r.json().get('models', [])
                ]
        except Exception as e:
            logger.warning(f"获取模型列表失败: {e}")

    def _find_best_match(self, target: str) -> str:
        if not self._models_cache:
            return target
        if target in self._models_cache:
            return target
        for m in self._models_cache:
            if m.startswith(f"{target}:"):
                return m
        return self._models_cache[0]

    def chat_stream_generator(self, messages: List[ChatMessage], config: Optional[LLMConfig] = None):
        """同步流式生成器，逐 token yield"""
        cfg = config or self._default_config
        payload = {
            "model": self._model,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "stream": True,
            "keep_alive": settings.ollama_keep_alive,
            "options": {
                "temperature": cfg.temperature,
                "top_p": cfg.top_p,
                "top_k": cfg.top_k,
                "num_predict": cfg.max_tokens,
                "repeat_penalty": cfg.repeat_penalty,
                "num_ctx": cfg.num_ctx,
                **({"num_gpu": cfg.num_gpu} if cfg.num_gpu >= 0 else {}),
            }
        }
        try:
            # 连接超时 60s，读取超时用配置值
            timeout = (60, settings.ollama_timeout)
            with requests.post(
                    f"{self._base_url}/api/chat",
                    json=payload,
                    stream=True,
                    timeout=timeout,
            ) as r:
                r.raise_for_status()
                for line in r.iter_lines():
                    if not line:
                        continue
                    try:
                        data = json.loads(line)
                        token = data.get("message", {}).get("content", "")
                        if token:
                            yield token
                        if data.get("done", False):
                            break
                    except json.JSONDecodeError:
                        continue
        except requests.exceptions.Timeout:
            logger.warning(f"流式请求超时（timeout={settings.ollama_timeout}s），已中断")
            return  # ← 中断生成器，不 yield 错误字符串
        except requests.exceptions.ConnectionError as e:
            logger.warning(f"流式连接中断: {e}")
            return
        except Exception as e:
            logger.error(f"流式请求异常: {e}")
            return

    def chat_with_tools(
            self,
            messages: List[ChatMessage],
            tools: List[Dict[str, Any]],
            config: Optional[LLMConfig] = None
    ) -> Dict[str, Any]:
        """支持 Function Calling 的对话，返回原始响应供 Agent 解析"""
        cfg = config or self._default_config
        payload = {
            "model": self._model,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "tools": tools,
            "stream": False,
            "keep_alive": settings.ollama_keep_alive,
            "options": {
                "temperature": cfg.temperature,
                "top_p": cfg.top_p,
                "top_k": cfg.top_k,
                "num_predict": cfg.max_tokens,
                "repeat_penalty": cfg.repeat_penalty,
                "num_ctx": cfg.num_ctx,
                **({"num_gpu": cfg.num_gpu} if cfg.num_gpu >= 0 else {}),
            }
        }
        try:
            r = requests.post(
                f"{self._base_url}/api/chat",
                json=payload,
                timeout=settings.ollama_timeout
            )
            if r.status_code == 200:
                data = r.json()
                msg = data.get("message", {})
                return {
                    "success": True,
                    "content": msg.get("content", ""),
                    "tool_calls": msg.get("tool_calls", []),
                    "done": data.get("done", True),
                }
            else:
                return {"success": False, "content": "", "tool_calls": [], "error": str(r.status_code)}
        except Exception as e:
            logger.error(f"chat_with_tools 异常: {e}")
            return {"success": False, "content": "", "tool_calls": [], "error": str(e)}

    def build_tool_schemas(self, tool_manager) -> List[Dict[str, Any]]:
        """把 ToolManager 里注册的工具转换为 Ollama Function Calling 格式"""
        schemas = []
        for name, tool in tool_manager.get_all_tools().items():
            properties = {}
            required = []
            for p in tool.parameters:
                prop_type = {
                    "str": "string",
                    "int": "integer",
                    "float": "number",
                    "bool": "boolean",
                    "list": "array",
                    "dict": "object",
                }.get(p.type, "string")

                properties[p.name] = {
                    "type": prop_type,
                    "description": p.description,
                }
                if p.default is not None:
                    properties[p.name]["default"] = p.default
                if p.required:
                    required.append(p.name)

            schemas.append({
                "type": "function",
                "function": {
                    "name": name,
                    "description": tool.description,
                    "parameters": {
                        "type": "object",
                        "properties": properties,
                        "required": required,
                    }
                }
            })
        return schemas
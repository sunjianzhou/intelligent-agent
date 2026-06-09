"""OpenAI 兼容 Provider，支持 OpenAI / 阿里云 / DeepSeek / 智谱等云端模型"""
from typing import List, Dict, Any, Optional
from loguru import logger
from services.base_provider import BaseLLMProvider, ChatMessage, LLMConfig, LLMResponse

def _to_dict(obj):
    """递归把任意对象转成 JSON 可序列化的 dict"""
    if isinstance(obj, dict):
        return {k: _to_dict(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_to_dict(i) for i in obj]
    if hasattr(obj, '__dict__'):
        return _to_dict(vars(obj))
    return obj


class OpenAIProvider(BaseLLMProvider):

    def __init__(self, api_key: str, base_url: str,
                 model: str, timeout: int = 120):
        self.api_key  = api_key
        self.base_url = base_url.rstrip("/")
        self.model    = model
        self.timeout  = timeout

    @property
    def current_model(self):
        return self.model

    @property
    def provider_name(self) -> str:
        return "openai_compatible"

    def available_models(self) -> List[str]:
        return [self.model]

    def check_connection(self) -> bool:
        try:
            self.chat([ChatMessage(role="user", content="hi")])
            return True
        except Exception:
            return False

    def chat_stream_generator(self, messages: List[ChatMessage], config=None):
        """同步流式生成器，逐 token yield"""
        import requests
        import json
        cfg = config or LLMConfig()
        payload = {
            "model": self.model,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "temperature": cfg.temperature,
            "max_tokens": cfg.max_tokens,
            "stream": True,
        }
        try:
            with requests.post(
                    f"{self.base_url}/chat/completions",
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                    timeout=(10, self.timeout),
                    stream=True,
            ) as resp:
                resp.raise_for_status()
                for line in resp.iter_lines():
                    if not line:
                        continue
                    line = line.decode("utf-8")
                    if line.startswith("data: "):
                        line = line[6:]
                    if line == "[DONE]":
                        break
                    try:
                        delta = json.loads(line)["choices"][0]["delta"]
                        token = delta.get("content", "")
                        if token:
                            yield token
                    except Exception:
                        continue
        except requests.exceptions.Timeout:
            logger.warning(f"云端流式请求超时，已中断")
            return
        except Exception as e:
            logger.warning(f"云端流式请求中断: {e}")
            return

    def chat(self, messages: List[ChatMessage],
             config: Optional[LLMConfig] = None) -> LLMResponse:
        import requests
        cfg = config or LLMConfig()
        payload = {
            "model": self.model,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "temperature": cfg.temperature,
            "max_tokens": cfg.max_tokens,
            "stream": False,
        }
        try:
            resp = requests.post(
                f"{self.base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json",
                },
                json=payload,
                timeout=self.timeout,
            )
            resp.raise_for_status()
            content = resp.json()["choices"][0]["message"]["content"]
            return LLMResponse(content=content, model=self.model, success=True)
        except Exception as e:
            logger.error(f"OpenAI chat 失败: {e}")
            return LLMResponse(content="", model=self.model, success=False, error=str(e))

    def chat_stream(self, messages: List[ChatMessage],
                    config: Optional[LLMConfig] = None):
        import requests, json
        cfg = config or LLMConfig()
        payload = {
            "model":       self.model,
            "messages":    [{"role": m.role, "content": m.content} for m in messages],
            "temperature": cfg.temperature,
            "max_tokens":  cfg.max_tokens,
            "stream":      True,
        }
        with requests.post(
            f"{self.base_url}/chat/completions",
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type":  "application/json",
            },
            json=payload,
            timeout=self.timeout,
            stream=True,
        ) as resp:
            resp.raise_for_status()
            for line in resp.iter_lines():
                if not line:
                    continue
                line = line.decode("utf-8")
                if line.startswith("data: "):
                    line = line[6:]
                if line == "[DONE]":
                    break
                try:
                    delta = json.loads(line)["choices"][0]["delta"]
                    token = delta.get("content", "")
                    if token:
                        yield token
                except Exception:
                    continue

    def chat_with_tools(self, messages: List[ChatMessage],
                        tools: List[Dict], config=None) -> Dict[str, Any]:
        """Function Calling，云端模型原生支持"""
        import requests
        cfg = config or LLMConfig()
        payload = {
            "model":       self.model,
            "messages":    [{"role": m.role, "content": m.content} for m in messages],
            "temperature": cfg.temperature,
            "max_tokens":  cfg.max_tokens,
            "tools":       tools,
            "tool_choice": "auto",
        }
        resp = requests.post(
            f"{self.base_url}/chat/completions",
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type":  "application/json",
            },
            json=payload,
            timeout=self.timeout,
        )
        resp.raise_for_status()
        msg = resp.json()["choices"][0]["message"]

        # 解析 tool_calls
        tool_calls = []
        if msg.get("tool_calls"):
            for tc in msg["tool_calls"]:
                import json as _json
                try:
                    args = _json.loads(tc["function"]["arguments"])
                except Exception:
                    args = {}
                tool_calls.append({
                    "name":      tc["function"]["name"],
                    "arguments": args,
                })

        return {
            "content":    msg.get("content", ""),
            "tool_calls": tool_calls,
        }

    def build_tool_schemas(self, tools) -> List[Dict]:
        schemas = []
        for tool in tools.values():
            try:
                schema = tool.get_schema()
                if isinstance(schema, dict):
                    name = schema.get("name", "")
                    description = schema.get("description", "")
                    parameters = schema.get("parameters", {})
                else:
                    name = getattr(schema, "name", "")
                    description = getattr(schema, "description", "")
                    parameters = getattr(schema, "parameters", {})

                # 递归转成纯 dict，处理 ToolParameter 等自定义对象
                parameters = _to_dict(parameters)

                schemas.append({
                    "type": "function",
                    "function": {
                        "name": name,
                        "description": description,
                        "parameters": parameters,
                    }
                })
            except Exception as e:
                logger.warning(f"工具 schema 构建失败: {e}")
                continue
        return schemas

    def build_tool_schemas_from(self, tools: Dict) -> List[Dict]:
        return self.build_tool_schemas(tools)

    def get_available_models(self) -> List[str]:
        return [self.model]
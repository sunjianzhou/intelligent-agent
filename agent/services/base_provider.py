"""LLM Provider 抽象基类"""
from abc import ABC, abstractmethod
from typing import Dict, Any, List, Optional
from dataclasses import dataclass


@dataclass
class ChatMessage:
    role: str     # system / user / assistant
    content: str


@dataclass
class LLMConfig:
    """模型参数配置，所有参数均有默认值，可按需覆盖"""
    temperature: float  = 0.7
    max_tokens: int     = 512
    top_p: float        = 0.9
    top_k: int          = 40
    repeat_penalty: float = 1.1
    num_ctx: int        = 2048
    # GPU 层数：-1 = Ollama 自动决定；正整数 = 强制只把 N 层放到 GPU 上，
    # 其余 layers 在 CPU 跑（用于大模型 + 低显存场景，如 dolphin 16GB + GTX1660 6GB）
    num_gpu: int        = -1


@dataclass
class LLMResponse:
    content: str
    model: str
    prompt_tokens: int  = 0
    output_tokens: int  = 0
    success: bool       = True
    error: str          = ''


class BaseLLMProvider(ABC):
    """所有 LLM Provider 必须实现的接口"""

    @property
    @abstractmethod
    def provider_name(self) -> str:
        """Provider 名称，如 ollama / openai / anthropic"""
        ...

    @property
    @abstractmethod
    def current_model(self) -> str:
        """当前使用的模型名"""
        ...

    @abstractmethod
    def available_models(self) -> List[str]:
        """返回该 Provider 下可用的模型列表"""
        ...

    @abstractmethod
    def check_connection(self) -> bool:
        """检查服务是否可用"""
        ...

    @abstractmethod
    def chat(
        self,
        messages: List[ChatMessage],
        config: Optional[LLMConfig] = None
    ) -> LLMResponse:
        """
        发送对话请求。
        messages: 完整的对话历史
        config:   本次请求的参数，None 时使用 Provider 默认值
        """
        ...

    def chat_simple(
        self,
        prompt: str,
        config: Optional[LLMConfig] = None
    ) -> LLMResponse:
        """单轮对话快捷方法，内部转为 messages 格式调用 chat()"""
        return self.chat(
            messages=[ChatMessage(role='user', content=prompt)],
            config=config
        )
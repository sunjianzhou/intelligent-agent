"""图片生成 Provider 抽象基类"""
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Optional


@dataclass
class ImageRequest:
    prompt: str
    size: str = "1024x1024"
    steps: int = 20
    guidance_scale: float = 7.5
    style: Optional[str] = None       # 附加风格词，如 "oil painting"
    negative_prompt: str = ""         # 负面提示词（SD 系列支持）
    extra: dict = field(default_factory=dict)  # Provider 专属扩展参数


@dataclass
class ImageResult:
    success: bool
    image_url: str = ""       # 远端 URL（云端 provider 直接返回）
    image_bytes: bytes = b""  # 二进制（本地 provider 直接返回）
    error: str = ""
    provider: str = ""
    model: str = ""


class BaseImageProvider(ABC):
    """所有图片生成 Provider 必须实现的接口"""

    @property
    @abstractmethod
    def provider_name(self) -> str:
        """Provider 标识，如 siliconflow / sd_webui / comfyui"""
        ...

    @property
    @abstractmethod
    def current_model(self) -> str:
        """当前使用的模型名"""
        ...

    @abstractmethod
    def is_available(self) -> bool:
        """检查 Provider 是否可用（API Key 存在 / 服务可达）"""
        ...

    @abstractmethod
    async def generate(self, req: ImageRequest) -> ImageResult:
        """
        异步生成图片。
        返回 ImageResult：
          - 云端 provider 通常填 image_url
          - 本地 provider 通常填 image_bytes（或下载后填 image_url）
        """
        ...

    def unavailable_result(self, reason: str) -> ImageResult:
        return ImageResult(success=False, error=reason, provider=self.provider_name)

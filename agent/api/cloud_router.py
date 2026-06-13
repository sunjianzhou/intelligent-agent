"""云端 LLM 服务商配置管理路由。"""
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel
from pathlib import Path
from typing import Optional, Callable
import json
import uuid
from loguru import logger

router = APIRouter(prefix="/api/cloud", tags=["cloud"])

_PROVIDERS_FILE = Path(__file__).parent.parent / "data" / "cloud_providers.json"

# 由 fastapi_app.py 在 provider 初始化完成后注入
_on_activate: Optional[Callable] = None
_on_deactivate: Optional[Callable] = None

KNOWN_PROVIDER_URLS: dict = {
    "openai":       "https://api.openai.com/v1",
    "dashscope":    "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "deepseek":     "https://api.deepseek.com/v1",
    "zhipu":        "https://open.bigmodel.cn/api/paas/v4",
    "moonshot":     "https://api.moonshot.cn/v1",
    "baidu":        "https://qianfan.baidubce.com/v2",
    "siliconflow":  "https://api.siliconflow.cn/v1",
}

KNOWN_PROVIDER_LABELS: dict = {
    "openai":       "OpenAI",
    "dashscope":    "阿里云百炼 (DashScope)",
    "deepseek":     "DeepSeek",
    "zhipu":        "智谱 AI",
    "moonshot":     "Moonshot (Kimi)",
    "baidu":        "百度千帆",
    "siliconflow":  "硅基流动 (SiliconFlow)",
}


class CloudProviderIn(BaseModel):
    name: str
    provider: str
    base_url: str = ""
    api_key: str = ""  # 编辑时留空 = 保持原密钥
    model: str


def set_callbacks(on_activate: Callable, on_deactivate: Callable) -> None:
    global _on_activate, _on_deactivate
    _on_activate = on_activate
    _on_deactivate = on_deactivate


def load_providers() -> list:
    """加载配置文件；fastapi_app.py startup 也会调用来恢复激活的服务商。"""
    try:
        if _PROVIDERS_FILE.exists():
            return json.loads(_PROVIDERS_FILE.read_text(encoding="utf-8"))
    except Exception as e:
        logger.warning(f"读取云端服务商配置失败: {e}")
    return []


def _save(providers: list) -> None:
    try:
        _PROVIDERS_FILE.parent.mkdir(parents=True, exist_ok=True)
        _PROVIDERS_FILE.write_text(
            json.dumps(providers, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    except Exception as e:
        logger.warning(f"保存云端服务商配置失败: {e}")


def _mask(key: str) -> str:
    if not key or len(key) <= 8:
        return "****"
    return key[:4] + "****" + key[-4:]


def _public(p: dict) -> dict:
    out = {k: v for k, v in p.items() if k != "api_key"}
    out["api_key_masked"] = _mask(p.get("api_key", ""))
    return out


@router.get("/providers")
async def list_providers(_req: Request):
    providers = load_providers()
    return {"providers": [_public(p) for p in providers]}


@router.get("/presets")
async def get_presets(_req: Request):
    presets = [
        {"key": k, "label": KNOWN_PROVIDER_LABELS.get(k, k), "url": v}
        for k, v in KNOWN_PROVIDER_URLS.items()
    ]
    return {"presets": presets}


@router.post("/providers")
async def create_provider(body: CloudProviderIn, _req: Request):
    providers = load_providers()
    pid = str(uuid.uuid4())[:8]
    entry = {
        "id": pid,
        "name": body.name,
        "provider": body.provider,
        "base_url": body.base_url or KNOWN_PROVIDER_URLS.get(body.provider.lower(), ""),
        "api_key": body.api_key,
        "model": body.model,
        "is_active": False,
    }
    providers.append(entry)
    _save(providers)
    return {"success": True, "provider": _public(entry)}


@router.put("/providers/{pid}")
async def update_provider(pid: str, body: CloudProviderIn, _req: Request):
    providers = load_providers()
    for p in providers:
        if p["id"] == pid:
            p["name"] = body.name
            p["provider"] = body.provider
            p["base_url"] = (
                body.base_url
                or KNOWN_PROVIDER_URLS.get(body.provider.lower(), p.get("base_url", ""))
            )
            if body.api_key:
                p["api_key"] = body.api_key
            p["model"] = body.model
            _save(providers)
            # 若当前是激活状态，同步更新全局 provider
            if p.get("is_active") and _on_activate:
                try:
                    eff_url = p["base_url"] or KNOWN_PROVIDER_URLS.get(p["provider"].lower(), "")
                    _on_activate(
                        base_url=eff_url,
                        api_key=p["api_key"],
                        model=p["model"],
                        provider_name=p["provider"],
                    )
                except Exception as e:
                    logger.warning(f"更新激活服务商 provider 实例失败: {e}")
            return {"success": True, "provider": _public(p)}
    raise HTTPException(status_code=404, detail="服务商不存在")


@router.delete("/providers/{pid}")
async def delete_provider(pid: str, _req: Request):
    providers = load_providers()
    before = len(providers)
    removed = next((p for p in providers if p["id"] == pid), None)
    providers = [p for p in providers if p["id"] != pid]
    if len(providers) == before:
        raise HTTPException(status_code=404, detail="服务商不存在")
    _save(providers)
    # 若删除的是激活服务商，自动切回 Ollama
    if removed and removed.get("is_active") and _on_deactivate:
        try:
            _on_deactivate()
        except Exception as e:
            logger.warning(f"删除激活服务商后切回 Ollama 出错: {e}")
    return {"success": True}


@router.post("/providers/{pid}/activate")
async def activate_provider(pid: str, _req: Request):
    providers = load_providers()
    target = next((p for p in providers if p["id"] == pid), None)
    if not target:
        raise HTTPException(status_code=404, detail="服务商不存在")

    for p in providers:
        p["is_active"] = p["id"] == pid
    _save(providers)

    if _on_activate:
        try:
            eff_url = target.get("base_url") or KNOWN_PROVIDER_URLS.get(
                target["provider"].lower(), ""
            )
            _on_activate(
                base_url=eff_url,
                api_key=target["api_key"],
                model=target["model"],
                provider_name=target["provider"],
            )
        except Exception as e:
            logger.error(f"激活云端服务商失败: {e}")
            raise HTTPException(status_code=500, detail=f"激活失败: {e}")

    return {"success": True, "active": _public(target)}


@router.post("/deactivate")
async def deactivate_provider(_req: Request):
    providers = load_providers()
    for p in providers:
        p["is_active"] = False
    _save(providers)

    if _on_deactivate:
        try:
            _on_deactivate()
        except Exception as e:
            logger.warning(f"切换回 Ollama 时出错: {e}")

    return {"success": True}

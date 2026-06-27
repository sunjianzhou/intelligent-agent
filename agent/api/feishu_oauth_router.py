"""飞书 OAuth 端点：authorize / callback / status。"""
import asyncio

import requests
from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import HTMLResponse, JSONResponse

from services.feishu_oauth import (
    OAuthNotAuthorizedError,
    OAuthRefreshExpiredError,
    exchange_code,
    get_auth_url,
    get_oauth_status,
)

router = APIRouter(prefix="/api/feishu/oauth", tags=["feishu-oauth"])

_HTML_SUCCESS = """<!DOCTYPE html><html><head><meta charset="utf-8">
<title>授权成功</title></head><body>
<h2>&#x2705; 飞书授权成功</h2>
<p>你已授权 agent 访问个人日历和任务，可以关闭此页面。</p>
</body></html>"""

_HTML_DENIED = """<!DOCTYPE html><html><head><meta charset="utf-8">
<title>授权被拒绝</title></head><body>
<h2>&#x274C; 拒绝授权</h2>
<p>你拒绝了飞书授权。如需重新授权，请向 agent 发送"给我飞书日历授权链接"。</p>
</body></html>"""


def _error_html(detail: str) -> str:
    return (
        '<!DOCTYPE html><html><head><meta charset="utf-8">'
        "<title>授权失败</title></head><body>"
        f"<h2>&#x274C; 授权失败</h2><p>{detail}</p></body></html>"
    )


@router.get("/authorize")
async def authorize(open_id: str = Query(..., description="用户 open_id")):
    """返回飞书 OAuth 授权链接。"""
    url = get_auth_url(open_id)
    return JSONResponse({"auth_url": url})


@router.get("/callback")
async def callback(
    code: str = Query(None),
    state: str = Query(None),
    error: str = Query(None),
):
    """接收飞书 OAuth 回调，完成 code 换 token。"""
    if error:
        return HTMLResponse(_HTML_DENIED)

    if not code or not state:
        raise HTTPException(status_code=400, detail="缺少 code 或 state 参数")

    try:
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, lambda: exchange_code(code, state))
    except ValueError as e:
        return HTMLResponse(content=_error_html(str(e)), status_code=400)
    except (RuntimeError, requests.RequestException) as e:
        return HTMLResponse(content=_error_html(f"飞书 token 换取失败: {e}"), status_code=400)

    return HTMLResponse(_HTML_SUCCESS)


@router.get("/status")
async def status(open_id: str = Query(..., description="用户 open_id")):
    """查询用户 OAuth 授权状态。"""
    return JSONResponse(get_oauth_status(open_id))

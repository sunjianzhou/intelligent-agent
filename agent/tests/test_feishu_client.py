import json
import pytest
import responses
import os
from unittest.mock import patch


TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
MSG_URL   = "https://open.feishu.cn/open-apis/im/v1/messages"


@responses.activate
def test_send_text_message():
    """im_message 工具发送 text 消息，验证请求体结构"""
    responses.add(responses.POST, TOKEN_URL,
                  json={"code": 0, "tenant_access_token": "tok-test", "expire": 7200})
    responses.add(responses.POST, MSG_URL, json={"code": 0}, match_querystring=False)

    with patch.dict(os.environ, {"FEISHU_APP_ID": "app1", "FEISHU_APP_SECRET": "sec1"}):
        import importlib
        import im.feishu_client as fc
        importlib.reload(fc)
        tool = fc.FeishuIMTool()
        result = tool.execute(
            receiver_id="ou_test",
            msg_type="text",
            content={"text": "Hello 飞书"},
        )

    msg_req = responses.calls[1]
    body    = json.loads(msg_req.request.body)
    assert body["receive_id"] == "ou_test"
    assert body["msg_type"]   == "text"
    assert "Hello 飞书" in json.loads(body["content"])["text"]
    assert result.get("code") == 0


@responses.activate
@pytest.mark.parametrize("receive_id_type", ["open_id", "union_id", "user_id", "chat_id"])
def test_all_receive_id_types(receive_id_type):
    """4 种 receive_id_type 均正确透传到查询参数"""
    responses.add(responses.POST, TOKEN_URL,
                  json={"code": 0, "tenant_access_token": "tok", "expire": 7200})
    responses.add(responses.POST, MSG_URL, json={"code": 0}, match_querystring=False)

    with patch.dict(os.environ, {"FEISHU_APP_ID": "a", "FEISHU_APP_SECRET": "s"}):
        import importlib
        import im.feishu_client as fc
        importlib.reload(fc)
        tool = fc.FeishuIMTool()
        tool.execute(receiver_id="id", msg_type="text",
                     content={"text": "t"}, receive_id_type=receive_id_type)

    assert receive_id_type in responses.calls[1].request.url


@responses.activate
def test_interactive_message_type():
    """interactive 消息：content 为 dict（卡片 JSON）"""
    responses.add(responses.POST, TOKEN_URL,
                  json={"code": 0, "tenant_access_token": "tok", "expire": 7200})
    responses.add(responses.POST, MSG_URL, json={"code": 0}, match_querystring=False)

    card = {"config": {}, "header": {"title": {"tag": "plain_text", "content": "标题"}}}
    with patch.dict(os.environ, {"FEISHU_APP_ID": "a", "FEISHU_APP_SECRET": "s"}):
        import importlib
        import im.feishu_client as fc
        importlib.reload(fc)
        tool = fc.FeishuIMTool()
        tool.execute(receiver_id="oc_group", msg_type="interactive",
                     content=card, receive_id_type="chat_id")

    body = json.loads(responses.calls[1].request.body)
    assert body["msg_type"] == "interactive"

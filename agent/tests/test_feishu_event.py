import pytest
from unittest.mock import patch
import os


def test_feishu_im_tool_name():
    """FeishuIMTool.name == im_message"""
    with patch.dict(os.environ, {"FEISHU_APP_ID": "app1", "FEISHU_APP_SECRET": "sec1"}):
        import importlib
        import im.feishu_client as fc
        importlib.reload(fc)
        tool = fc.FeishuIMTool()
        assert tool.name == "im_message"


def test_feishu_im_tool_parameter_schema():
    """工具参数包含 receiver_id, msg_type, content"""
    with patch.dict(os.environ, {"FEISHU_APP_ID": "a", "FEISHU_APP_SECRET": "s"}):
        import importlib
        import im.feishu_client as fc
        importlib.reload(fc)
        tool = fc.FeishuIMTool()
        param_names = [p.name for p in tool.parameters]
        assert "receiver_id" in param_names
        assert "msg_type"    in param_names
        assert "content"     in param_names


def test_feishu_im_tool_default_receive_id_type():
    """receive_id_type 默认值为 open_id"""
    with patch.dict(os.environ, {"FEISHU_APP_ID": "a", "FEISHU_APP_SECRET": "s"}):
        import importlib
        import im.feishu_client as fc
        importlib.reload(fc)
        tool = fc.FeishuIMTool()
        rid_param = next(p for p in tool.parameters if p.name == "receive_id_type")
        assert rid_param.required is False
        assert rid_param.default == "open_id"

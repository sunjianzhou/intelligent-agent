"""飞书写工具测试：feishu_calendar_create / feishu_task_write。"""
import pytest
import responses as resp_lib
from unittest.mock import patch

OPEN_ID = "ou_test_user"
CAL_URL = "https://open.feishu.cn/open-apis/calendar/v4/calendars/cal_x/events"
TASK_CREATE_URL = "https://open.feishu.cn/open-apis/task/v2/tasks"
TASK_COMPLETE_URL = "https://open.feishu.cn/open-apis/task/v2/tasks/task_001/complete"


def _mock_token(open_id):  # 同步 mock
    return "u-user-token"


# ── feishu_calendar_create ────────────────────────────────────────────────────

@resp_lib.activate
def test_calendar_create_posts_event():
    resp_lib.add(resp_lib.POST, CAL_URL, json={"code": 0, "data": {"event": {"event_id": "ev_1"}}})
    with patch("tools.builtin_tools.feishu_calendar_create.get_valid_token", side_effect=_mock_token):
        from tools.builtin_tools.feishu_calendar_create import FeishuCalendarCreateTool
        tool = FeishuCalendarCreateTool()
        result = tool.execute(
            open_id=OPEN_ID,
            calendar_id="cal_x",
            summary="周会",
            start_time="2026-07-01T10:00:00+08:00",
            end_time="2026-07-01T11:00:00+08:00",
        )
    assert result["code"] == 0
    req_body = resp_lib.calls[0].request.body
    if isinstance(req_body, bytes):
        req_body = req_body.decode("utf-8")
    assert "周会" in req_body
    assert resp_lib.calls[0].request.headers["Authorization"] == "Bearer u-user-token"


def test_calendar_create_raises_without_user_token():
    """写工具不提供 tenant fallback，无授权时直接抛出。"""
    from services.feishu_oauth import OAuthNotAuthorizedError

    def raise_no_auth(open_id):
        raise OAuthNotAuthorizedError("未授权")

    with patch("tools.builtin_tools.feishu_calendar_create.get_valid_token", side_effect=raise_no_auth):
        from tools.builtin_tools.feishu_calendar_create import FeishuCalendarCreateTool
        tool = FeishuCalendarCreateTool()
        with pytest.raises(OAuthNotAuthorizedError):
            tool.execute(
                open_id=OPEN_ID,
                calendar_id="cal_x",
                summary="测试",
                start_time="2026-07-01T10:00:00+08:00",
                end_time="2026-07-01T11:00:00+08:00",
            )


# ── feishu_task_write ─────────────────────────────────────────────────────────

@resp_lib.activate
def test_task_write_creates_task():
    resp_lib.add(resp_lib.POST, TASK_CREATE_URL, json={"code": 0, "data": {"task": {"guid": "task_001"}}})
    with patch("tools.builtin_tools.feishu_task_write.get_valid_token", side_effect=_mock_token):
        from tools.builtin_tools.feishu_task_write import FeishuTaskWriteTool
        tool = FeishuTaskWriteTool()
        result = tool.execute(open_id=OPEN_ID, action="create", summary="提交代码")
    assert result["code"] == 0
    req_body = resp_lib.calls[0].request.body
    if isinstance(req_body, bytes):
        req_body = req_body.decode("utf-8")
    assert "提交代码" in req_body


@resp_lib.activate
def test_task_write_completes_task():
    resp_lib.add(resp_lib.POST, TASK_COMPLETE_URL, json={"code": 0})
    with patch("tools.builtin_tools.feishu_task_write.get_valid_token", side_effect=_mock_token):
        from tools.builtin_tools.feishu_task_write import FeishuTaskWriteTool
        tool = FeishuTaskWriteTool()
        result = tool.execute(open_id=OPEN_ID, action="complete", task_id="task_001")
    assert result["code"] == 0


def test_task_write_raises_on_invalid_action():
    with patch("tools.builtin_tools.feishu_task_write.get_valid_token", side_effect=_mock_token):
        from tools.builtin_tools.feishu_task_write import FeishuTaskWriteTool
        tool = FeishuTaskWriteTool()
        with pytest.raises(ValueError, match="action"):
            tool.execute(open_id=OPEN_ID, action="delete")

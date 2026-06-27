"""测试飞书日历/待办只读工具（TODO-82）：feishu_calendar_list / feishu_task_list。"""
import json
import os
from unittest.mock import patch

import responses

TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"


@responses.activate
def test_calendar_list_queries_events_with_time_range():
    responses.add(responses.POST, TOKEN_URL,
                  json={"code": 0, "tenant_access_token": "tok-cal", "expire": 7200})
    events_url = "https://open.feishu.cn/open-apis/calendar/v4/calendars/cal_123/events"
    responses.add(responses.GET, events_url,
                  json={"code": 0, "data": {"items": [{"summary": "周会"}]}})

    with patch.dict(os.environ, {"FEISHU_APP_ID": "app1", "FEISHU_APP_SECRET": "sec1"}):
        import importlib
        import im.feishu_client as fc
        importlib.reload(fc)
        from tools.builtin_tools.feishu_calendar import FeishuCalendarTool
        tool = FeishuCalendarTool()
        result = tool.execute(
            calendar_id="cal_123", start_time="1700000000", end_time="1700086400",
        )

    assert result["code"] == 0
    assert result["data"]["items"][0]["summary"] == "周会"

    events_req = responses.calls[1]
    assert "start_time=1700000000" in events_req.request.url
    assert "end_time=1700086400" in events_req.request.url
    assert events_req.request.headers["Authorization"] == "Bearer tok-cal"


@responses.activate
def test_calendar_list_default_page_size():
    responses.add(responses.POST, TOKEN_URL,
                  json={"code": 0, "tenant_access_token": "tok", "expire": 7200})
    events_url = "https://open.feishu.cn/open-apis/calendar/v4/calendars/cal_x/events"
    responses.add(responses.GET, events_url, json={"code": 0, "data": {"items": []}})

    with patch.dict(os.environ, {"FEISHU_APP_ID": "a", "FEISHU_APP_SECRET": "s"}):
        import importlib
        import im.feishu_client as fc
        importlib.reload(fc)
        from tools.builtin_tools.feishu_calendar import FeishuCalendarTool
        tool = FeishuCalendarTool()
        tool.execute(calendar_id="cal_x", start_time="1", end_time="2")

    assert "page_size=50" in responses.calls[1].request.url


@responses.activate
def test_task_list_queries_all_tasks_when_no_tasklist():
    responses.add(responses.POST, TOKEN_URL,
                  json={"code": 0, "tenant_access_token": "tok-task", "expire": 7200})
    tasks_url = "https://open.feishu.cn/open-apis/task/v2/tasks"
    responses.add(responses.GET, tasks_url,
                  json={"code": 0, "data": {"items": [{"summary": "提交周报"}]}})

    with patch.dict(os.environ, {"FEISHU_APP_ID": "app1", "FEISHU_APP_SECRET": "sec1"}):
        import importlib
        import im.feishu_client as fc
        importlib.reload(fc)
        from tools.builtin_tools.feishu_task import FeishuTaskTool
        tool = FeishuTaskTool()
        result = tool.execute()

    assert result["data"]["items"][0]["summary"] == "提交周报"
    task_req = responses.calls[1]
    assert "tasklist_guid" not in task_req.request.url
    assert task_req.request.headers["Authorization"] == "Bearer tok-task"


@responses.activate
def test_task_list_filters_by_tasklist_guid():
    responses.add(responses.POST, TOKEN_URL,
                  json={"code": 0, "tenant_access_token": "tok", "expire": 7200})
    tasks_url = "https://open.feishu.cn/open-apis/task/v2/tasks"
    responses.add(responses.GET, tasks_url, json={"code": 0, "data": {"items": []}})

    with patch.dict(os.environ, {"FEISHU_APP_ID": "a", "FEISHU_APP_SECRET": "s"}):
        import importlib
        import im.feishu_client as fc
        importlib.reload(fc)
        from tools.builtin_tools.feishu_task import FeishuTaskTool
        tool = FeishuTaskTool()
        tool.execute(tasklist_guid="tl_abc")

    assert "tasklist_guid=tl_abc" in responses.calls[1].request.url


@responses.activate
def test_calendar_list_logs_warning_on_nonzero_code(caplog):
    responses.add(responses.POST, TOKEN_URL,
                  json={"code": 0, "tenant_access_token": "tok", "expire": 7200})
    events_url = "https://open.feishu.cn/open-apis/calendar/v4/calendars/cal_err/events"
    responses.add(responses.GET, events_url, json={"code": 99991663, "msg": "no permission"})

    with patch.dict(os.environ, {"FEISHU_APP_ID": "a", "FEISHU_APP_SECRET": "s"}):
        import importlib
        import im.feishu_client as fc
        importlib.reload(fc)
        from tools.builtin_tools.feishu_calendar import FeishuCalendarTool
        tool = FeishuCalendarTool()
        result = tool.execute(calendar_id="cal_err", start_time="1", end_time="2")

    assert result["code"] == 99991663


# ── 新增：open_id 存在时优先用 user_access_token ─────────────────────────────

@responses.activate
def test_calendar_list_uses_user_token_when_open_id_provided():
    """有 open_id + 已授权时，发出的请求带 user_access_token。"""
    events_url = "https://open.feishu.cn/open-apis/calendar/v4/calendars/cal_u/events"
    responses.add(responses.GET, events_url, json={"code": 0, "data": {"items": []}})

    def fake_get_valid_token(open_id):   # 同步 mock
        return "u-user-access-token"

    with patch("tools.builtin_tools.feishu_calendar.get_valid_token", side_effect=fake_get_valid_token):
        from tools.builtin_tools.feishu_calendar import FeishuCalendarTool
        tool = FeishuCalendarTool()
        result = tool.execute(calendar_id="cal_u", start_time="1", end_time="2", open_id="ou_test")

    assert responses.calls[0].request.headers["Authorization"] == "Bearer u-user-access-token"
    assert result["code"] == 0


@responses.activate
def test_calendar_list_falls_back_to_tenant_when_not_authorized():
    """有 open_id 但未授权时，fallback 到 tenant_access_token。"""
    from services.feishu_oauth import OAuthNotAuthorizedError

    TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
    events_url = "https://open.feishu.cn/open-apis/calendar/v4/calendars/cal_fb/events"
    responses.add(responses.POST, TOKEN_URL,
                  json={"code": 0, "tenant_access_token": "tok-tenant", "expire": 7200})
    responses.add(responses.GET, events_url, json={"code": 0, "data": {"items": []}})

    def raise_not_auth(open_id):   # 同步 mock
        raise OAuthNotAuthorizedError("未授权")

    with patch("tools.builtin_tools.feishu_calendar.get_valid_token", side_effect=raise_not_auth), \
         patch.dict(os.environ, {"FEISHU_APP_ID": "a", "FEISHU_APP_SECRET": "s"}):
        import importlib
        import im.feishu_client as fc
        importlib.reload(fc)
        from tools.builtin_tools.feishu_calendar import FeishuCalendarTool
        tool = FeishuCalendarTool()
        result = tool.execute(calendar_id="cal_fb", start_time="1", end_time="2", open_id="ou_test")

    assert responses.calls[-1].request.headers["Authorization"] == "Bearer tok-tenant"

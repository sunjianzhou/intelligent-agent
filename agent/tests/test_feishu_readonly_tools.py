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

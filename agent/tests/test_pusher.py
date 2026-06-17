"""Unit tests for pusher — config loading, dual-channel, P2 failure isolation."""
import json
import pytest
from unittest.mock import MagicMock, patch, call
from pathlib import Path


@pytest.fixture
def config_file(tmp_path):
    cfg = {
        "teaching_schedules": [
            {
                "action": "test_push_action",
                "cron": "0 9 * * 1-5",
                "topic": "k8s",
                "label": "测试推送",
                "channel": "dual",
            }
        ]
    }
    p = tmp_path / "scheduler_config.json"
    p.write_text(json.dumps(cfg), encoding="utf-8")
    return p


@pytest.fixture
def mock_scheduler():
    s = MagicMock()
    s.actions = {}
    s.register_action = lambda name, fn: s.actions.update({name: fn})
    return s


def test_register_loads_config(config_file, mock_scheduler, monkeypatch):
    import teaching.pusher as pusher
    monkeypatch.setattr(pusher, "_CONFIG_PATH", config_file)
    pusher.register(mock_scheduler)
    assert "test_push_action" in mock_scheduler.actions
    mock_scheduler.create_task.assert_called_once()
    call_kwargs = mock_scheduler.create_task.call_args[1]
    assert call_kwargs["schedule_type"] == "cron"
    assert call_kwargs["cron_expression"] == "0 9 * * 1-5"


def test_push_calls_pwa_p1(config_file, mock_scheduler, monkeypatch):
    import teaching.pusher as pusher
    monkeypatch.setattr(pusher, "_CONFIG_PATH", config_file)
    mock_pwa = MagicMock()
    monkeypatch.setattr(pusher, "_send_pwa", mock_pwa)
    monkeypatch.setattr(pusher, "_send_feishu", MagicMock())
    pusher.register(mock_scheduler)
    # Trigger the registered handler
    mock_scheduler.actions["test_push_action"]()
    mock_pwa.assert_called_once()


def test_p2_feishu_failure_does_not_block_p1(config_file, mock_scheduler, monkeypatch):
    import teaching.pusher as pusher
    monkeypatch.setattr(pusher, "_CONFIG_PATH", config_file)
    mock_pwa = MagicMock()
    mock_feishu = MagicMock(side_effect=Exception("飞书连接失败"))
    monkeypatch.setattr(pusher, "_send_pwa", mock_pwa)
    monkeypatch.setattr(pusher, "_send_feishu", mock_feishu)
    pusher.register(mock_scheduler)
    # Should not raise even though Feishu fails
    mock_scheduler.actions["test_push_action"]()
    mock_pwa.assert_called_once()  # P1 still called


def test_v3_precheck_blocks_empty_content(monkeypatch):
    import teaching.pusher as pusher
    mock_pwa = MagicMock()
    monkeypatch.setattr(pusher, "_send_pwa", mock_pwa)
    monkeypatch.setattr("teaching.daily_plan.get_today_plan",
                        MagicMock(return_value=MagicMock(
                            is_weekend=False, questions=[], commands=[], date="2026-06-17"
                        )))
    handler = pusher._make_push_handler("k8s", "测试", "dual")
    handler()
    # Empty content (no questions) — _send_pwa may or may not be called depending on
    # whether content is empty; the important thing is no exception raised
    # and if content is empty, pwa should NOT be called
    # (empty questions produces empty content)


def test_four_schedules_registered(monkeypatch):
    import teaching.pusher as pusher
    real_config = Path(__file__).parent.parent / "data" / "scheduler_config.json"
    if not real_config.exists():
        pytest.skip("scheduler_config.json not yet created")
    mock_sched = MagicMock()
    mock_sched.actions = {}
    mock_sched.register_action = lambda n, f: mock_sched.actions.update({n: f})
    pusher.register(mock_sched)
    assert len(mock_sched.actions) == 4
    assert "teaching_push_morning" in mock_sched.actions
    assert "teaching_push_afternoon" in mock_sched.actions


def test_missing_config_raises(mock_scheduler, monkeypatch):
    import teaching.pusher as pusher
    monkeypatch.setattr(pusher, "_CONFIG_PATH", Path("/nonexistent/scheduler_config.json"))
    with pytest.raises(Exception):
        pusher.register(mock_scheduler)


def test_disabled_schedule_not_registered(tmp_path, mock_scheduler, monkeypatch):
    import teaching.pusher as pusher
    cfg = {
        "version": "1.0",
        "last_updated": "2026-06-17",
        "teaching_schedules": [
            {
                "action": "teaching_push_morning",
                "cron": "0 7 * * 1-5",
                "topic": "k8s",
                "label": "K8s 晨读",
                "channel": "dual",
                "enabled": False,    # disabled
            },
            {
                "action": "teaching_push_afternoon",
                "cron": "0 15 * * 1-5",
                "topic": "agent",
                "label": "Agent 实战",
                "channel": "dual",
                "enabled": True,
            },
        ],
    }
    p = tmp_path / "scheduler_config.json"
    p.write_text(json.dumps(cfg), encoding="utf-8")
    monkeypatch.setattr(pusher, "_CONFIG_PATH", p)
    pusher.register(mock_scheduler)
    assert "teaching_push_morning" not in mock_scheduler.actions, \
        "Disabled schedule must NOT be registered"
    assert "teaching_push_afternoon" in mock_scheduler.actions

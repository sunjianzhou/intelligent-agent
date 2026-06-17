"""推送节奏：从 scheduler_config.json 加载 4 个 cron，双通道推送。"""
# CONFIG_PATH 解析策略与 wrong_book.py 一致，详见 teaching/wrong_book.py:14
import json
import os
from pathlib import Path
from typing import Callable
from loguru import logger

from teaching.daily_plan import get_today_plan

_CONFIG_PATH = Path(__file__).parent.parent / "data" / "scheduler_config.json"


# ── v3.0 自检 ────────────────────────────────────────────────────────────────

def _v3_precheck(content: str) -> bool:
    if not content.strip():
        logger.warning("[v3.0 自检] content 为空，跳过本次推送")
        return False
    return True


# ── 双通道推送 ────────────────────────────────────────────────────────────────

def _send_pwa(content: str) -> None:
    """P1：写入前端轮询通知队列。"""
    from scheduler.simple_scheduler import _push_notification
    _push_notification(content, role="assistant")


def _send_feishu(content: str) -> None:
    """P2：飞书 IM，独立 try/except，失败仅 warning 不阻断 P1。"""
    app_id = os.environ.get("FEISHU_APP_ID", "")
    receiver = os.environ.get("FEISHU_RECEIVER_ID", "")
    if not app_id or not receiver:
        logger.warning("[飞书 P2] FEISHU_APP_ID 或 FEISHU_RECEIVER_ID 未配置，跳过飞书推送")
        return
    from im.feishu_client import FeishuIMTool
    FeishuIMTool().execute(
        receiver_id=receiver,
        msg_type="text",
        content={"text": content},
    )
    logger.info("[飞书 P2] 推送成功")


# ── 推送内容构建 ──────────────────────────────────────────────────────────────

def _make_push_handler(topic: str, label: str, channel: str) -> Callable:
    def handler() -> None:
        plan = get_today_plan(topic)
        if plan.is_weekend:
            lines = [f"【{label}·周末实操】"]
            lines += [f"- {c}" for c in plan.commands]
        else:
            lines = [f"【{label}·今日练习】 {plan.date}"]
            for i, q in enumerate(plan.questions, 1):
                lines.append(f"\nQ{i}. {q.text}")
                if q.options:
                    for k, v in q.options.items():
                        lines.append(f"  {k}. {v}")
        content = "\n".join(lines)

        if not _v3_precheck(content):
            return

        _send_pwa(content)

        if channel == "dual":
            try:
                _send_feishu(content)
            except Exception as exc:
                logger.warning(f"[飞书 P2] 推送失败（不影响 P1）: {exc}")

    return handler


# ── 注册入口 ──────────────────────────────────────────────────────────────────

def register(scheduler) -> None:
    config = json.loads(_CONFIG_PATH.read_text(encoding="utf-8"))
    for entry in config["teaching_schedules"]:
        if not entry.get("enabled", True):
            logger.info(f"[TeachingPusher] 已跳过（enabled=false）: {entry['action']}")
            continue
        action_name = entry["action"]
        handler = _make_push_handler(
            topic=entry["topic"],
            label=entry["label"],
            channel=entry.get("channel", "dual"),
        )
        scheduler.register_action(action_name, handler)
        scheduler.create_task(
            name=entry["label"],
            action=action_name,
            schedule_type="cron",
            cron_expression=entry["cron"],
        )
        logger.info(f"[TeachingPusher] 已注册: {action_name}  cron={entry['cron']}")

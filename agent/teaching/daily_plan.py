"""每日计划生成：周中差异化题型 + 周末实操命令。"""
import random
from dataclasses import dataclass, field
from datetime import datetime
from typing import List
from teaching.question_bank import Question, get_questions


@dataclass
class DailyPlan:
    date: str
    topic: str
    is_weekend: bool
    questions: List[Question]
    commands: List[str] = field(default_factory=list)


def get_today_plan(topic: str) -> DailyPlan:
    today = datetime.now()
    date_str = today.strftime("%Y-%m-%d")
    is_weekend = today.weekday() >= 5

    if is_weekend:
        return DailyPlan(
            date=date_str,
            topic=topic,
            is_weekend=True,
            questions=[],
            commands=_get_weekend_commands(topic),
        )

    # 周中：40% 选择 + 30% 填空 + ≤2 简答，共 5 题
    all_qs = get_questions(topic=topic)
    choices = [q for q in all_qs if q.type == "choice"]
    fills = [q for q in all_qs if q.type == "fill"]
    shorts = [q for q in all_qs if q.type == "short_answer"]

    selected = (
        random.sample(choices, min(2, len(choices))) +
        random.sample(fills, min(2, len(fills))) +
        random.sample(shorts, min(1, len(shorts)))
    )
    random.shuffle(selected)

    return DailyPlan(
        date=date_str,
        topic=topic,
        is_weekend=False,
        questions=selected,
        commands=[],
    )


def _get_weekend_commands(topic: str) -> List[str]:
    shorts = get_questions(topic=topic, question_type="short_answer")
    return [q.text for q in shorts]

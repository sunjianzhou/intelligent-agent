"""Unit tests for daily_plan — weekday/weekend, topic routing, question ratio."""
import pytest
from unittest.mock import patch
from datetime import datetime
from teaching.daily_plan import get_today_plan, DailyPlan


def _mock_weekday(weekday: int):
    """Return a context manager that patches datetime.now().weekday()."""
    fake = datetime(2026, 6, 16 + weekday)  # Mon=0 … Sun=6
    return patch("teaching.daily_plan.datetime", wraps=datetime,
                 **{"now.return_value": fake})


def test_weekday_returns_questions():
    with _mock_weekday(0):  # Monday
        plan = get_today_plan("k8s")
    assert plan.is_weekend is False
    assert len(plan.questions) > 0


def test_weekend_returns_no_questions():
    with _mock_weekday(5):  # Saturday
        plan = get_today_plan("k8s")
    assert plan.is_weekend is True
    assert plan.questions == []


def test_weekend_returns_commands():
    with _mock_weekday(6):  # Sunday
        plan = get_today_plan("k8s")
    assert isinstance(plan.commands, list)
    assert len(plan.commands) > 0


def test_weekday_has_choice_and_fill():
    with _mock_weekday(1):  # Tuesday
        plan = get_today_plan("k8s")
    types = {q.type for q in plan.questions}
    assert "choice" in types
    assert "fill" in types


def test_k8s_review_uses_k8s_pool():
    with _mock_weekday(0):
        plan_review = get_today_plan("k8s_review")
        plan_k8s = get_today_plan("k8s")
    review_ids = {q.id for q in plan_review.questions}
    k8s_ids = {q.id for q in plan_k8s.questions}
    assert review_ids.issubset(k8s_ids | {"k8s-001", "k8s-002", "k8s-003",
                                           "k8s-004", "k8s-005", "k8s-006",
                                           "k8s-007", "k8s-008"})


def test_plan_date_is_today():
    plan = get_today_plan("k8s")
    assert plan.date == datetime.now().strftime("%Y-%m-%d")


def test_weekday_short_answer_at_most_two():
    # Run 10 times to account for randomness
    for _ in range(10):
        with _mock_weekday(2):
            plan = get_today_plan("k8s")
        short_count = sum(1 for q in plan.questions if q.type == "short_answer")
        assert short_count <= 2, f"Got {short_count} short_answer questions, max is 2"

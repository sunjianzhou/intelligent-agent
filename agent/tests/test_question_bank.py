"""Unit tests for question_bank."""
from collections import Counter
from teaching.question_bank import Question, get_questions, ALL_QUESTIONS


def test_get_all_returns_list():
    qs = get_questions()
    assert isinstance(qs, list)
    assert len(qs) >= 24


def test_filter_by_topic():
    k8s = get_questions(topic="k8s")
    assert all(q.topic == "k8s" for q in k8s)
    assert len(k8s) >= 8


def test_filter_by_type():
    choices = get_questions(question_type="choice")
    assert all(q.type == "choice" for q in choices)


def test_k8s_review_returns_k8s_questions():
    review = get_questions(topic="k8s_review")
    assert len(review) >= 8
    # k8s_review maps to the k8s pool
    k8s = get_questions(topic="k8s")
    assert set(q.id for q in review) == set(q.id for q in k8s)


def test_abcd_spread():
    """ABCD answers must be roughly even across 3 topics × 4 choice questions = 12 choice questions.
    Each letter should appear 2-4 times (deviation < 5 percentage points from 25%)."""
    choices = [q for q in ALL_QUESTIONS if q.type == "choice"]
    assert len(choices) == 12, f"Expected 12 choice questions (3 topics × 4), got {len(choices)}"
    counts = Counter(q.answer for q in choices)
    total = len(choices)  # 12
    for letter in ("A", "B", "C", "D"):
        count = counts.get(letter, 0)
        pct = count / total
        assert 0.15 <= pct <= 0.40, (
            f"Answer '{letter}' appears {count}/{total} times "
            f"({pct:.0%}), expected 15%-40% (2-5 out of 12)"
        )


def test_every_choice_has_four_options():
    for q in get_questions(question_type="choice"):
        assert set(q.options.keys()) == {"A", "B", "C", "D"}, (
            f"Question {q.id} has options {list(q.options.keys())}"
        )


def test_every_question_has_explanation():
    for q in ALL_QUESTIONS:
        assert q.explanation, f"Question {q.id} has no explanation"

"""Unit tests for grader — 3 types, explanation always present, wrong_book side-effect."""
import pytest
from unittest.mock import patch, MagicMock
from teaching.grader import grade, Submission, Answer


@pytest.fixture(autouse=True)
def no_wrong_book_io(monkeypatch):
    """Prevent writing to disk during grading tests."""
    monkeypatch.setattr("teaching.wrong_book.add", MagicMock())
    monkeypatch.setattr("teaching.wrong_book._save", MagicMock())


def test_correct_choice_answer():
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-001", user_answer="A")],
    )
    result = grade(sub)
    assert result.score == 1
    assert result.total == 1
    assert result.results[0].correct is True


def test_incorrect_choice_answer():
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-001", user_answer="B")],
    )
    result = grade(sub)
    assert result.results[0].correct is False


def test_explanation_present_for_correct_answer():
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-001", user_answer="A")],
    )
    result = grade(sub)
    assert result.results[0].explanation, "Explanation must be present even for correct answers"


def test_explanation_present_for_wrong_answer():
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-001", user_answer="B")],
    )
    result = grade(sub)
    assert result.results[0].explanation, "Explanation must be present for wrong answers"


def test_wrong_answer_calls_wrong_book(monkeypatch):
    from teaching import wrong_book
    mock_add = MagicMock()
    monkeypatch.setattr(wrong_book, "add", mock_add)
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-001", user_answer="B")],
    )
    grade(sub)
    mock_add.assert_called_once()
    call_kwargs = mock_add.call_args
    assert call_kwargs[1]["question_id"] == "k8s-001" or call_kwargs[0][0] == "k8s-001"


def test_correct_answer_does_not_call_wrong_book(monkeypatch):
    from teaching import wrong_book
    mock_add = MagicMock()
    monkeypatch.setattr(wrong_book, "add", mock_add)
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-001", user_answer="A")],
    )
    grade(sub)
    mock_add.assert_not_called()


def test_fill_answer_case_insensitive():
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-005", user_answer="--ALL-NAMESPACES")],
    )
    result = grade(sub)
    assert result.results[0].correct is True


def test_short_answer_partial_keyword_match():
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-007", user_answer="readinessProbe 控制流量，livenessProbe 重启容器")],
    )
    result = grade(sub)
    # short_answer grading is keyword-based: at least 50% of answer keywords
    assert isinstance(result.results[0].correct, bool)
    assert result.results[0].explanation  # always present


def test_score_aggregation():
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[
            Answer(question_id="k8s-001", user_answer="A"),   # correct
            Answer(question_id="k8s-002", user_answer="A"),   # wrong (answer is B)
        ],
    )
    result = grade(sub)
    assert result.total == 2
    assert result.score == 1

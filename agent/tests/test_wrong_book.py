"""Unit tests for wrong_book — dedup, resolve, list."""
import json
import pytest
from pathlib import Path
from unittest.mock import patch


@pytest.fixture(autouse=True)
def tmp_memory(tmp_path, monkeypatch):
    """Redirect _BASE to a temp directory for each test."""
    import teaching.wrong_book as wb
    monkeypatch.setattr(wb, "_BASE", tmp_path)
    yield tmp_path


from teaching.wrong_book import add, resolve, list_records


def test_add_creates_record():
    add("k8s-001", "k8s", "B", "A")
    records = list_records("k8s")
    assert len(records) == 1
    r = records[0]
    assert r["question_id"] == "k8s-001"
    assert r["wrong_count"] == 1
    assert r["resolved"] is False


def test_add_deduplicates_same_question():
    add("k8s-001", "k8s", "B", "A")
    add("k8s-001", "k8s", "C", "A")  # second wrong answer
    records = list_records("k8s")
    assert len(records) == 1, "Same question_id must not create duplicate entry"
    assert records[0]["wrong_count"] == 2
    assert records[0]["user_answer"] == "C"  # updated to latest


def test_add_different_questions_creates_two_records():
    add("k8s-001", "k8s", "B", "A")
    add("k8s-002", "k8s", "A", "B")
    assert len(list_records("k8s")) == 2


def test_resolve_marks_record():
    add("k8s-001", "k8s", "B", "A")
    ok = resolve("k8s-001", "k8s")
    assert ok is True
    records = list_records("k8s", include_resolved=True)
    r = next(r for r in records if r["question_id"] == "k8s-001")
    assert r["resolved"] is True
    assert r["resolved_time"] is not None


def test_list_excludes_resolved_by_default():
    add("k8s-001", "k8s", "B", "A")
    resolve("k8s-001", "k8s")
    assert list_records("k8s") == []
    assert len(list_records("k8s", include_resolved=True)) == 1


def test_resolve_nonexistent_returns_false():
    assert resolve("nonexistent", "k8s") is False


def test_list_sorted_by_last_wrong_time_desc():
    add("k8s-001", "k8s", "B", "A")
    add("k8s-002", "k8s", "A", "B")
    records = list_records("k8s")
    times = [r["last_wrong_time"] for r in records]
    assert times == sorted(times, reverse=True)

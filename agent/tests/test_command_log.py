"""Unit tests for command_log — append-only, accumulate, not overwrite."""
import pytest
from teaching.command_log import append, _path


@pytest.fixture(autouse=True)
def tmp_memory(tmp_path, monkeypatch):
    import teaching.command_log as cl
    monkeypatch.setattr(cl, "_BASE", tmp_path)
    yield tmp_path


def test_append_creates_file():
    append("k8s", "kubectl get pods", "列出当前命名空间所有 Pod")
    p = _path("k8s")
    assert p.exists()
    content = p.read_text(encoding="utf-8")
    assert "kubectl get pods" in content


def test_append_accumulates():
    append("k8s", "kubectl get pods", "列出 Pod")
    append("k8s", "kubectl describe pod", "查看 Pod 详情")
    content = _path("k8s").read_text(encoding="utf-8")
    assert "kubectl get pods" in content
    assert "kubectl describe pod" in content


def test_append_does_not_overwrite():
    append("k8s", "kubectl get pods", "第一条")
    first = _path("k8s").read_text(encoding="utf-8")
    append("k8s", "kubectl logs", "第二条")
    second = _path("k8s").read_text(encoding="utf-8")
    assert "kubectl get pods" in second, "First entry must survive after second append"
    assert len(second) > len(first)


def test_different_topics_use_different_files():
    append("k8s", "kubectl get pods", "K8s 命令")
    append("llm", "ollama run", "LLM 命令")
    k8s_content = _path("k8s").read_text(encoding="utf-8")
    llm_content = _path("llm").read_text(encoding="utf-8")
    assert "kubectl get pods" not in llm_content
    assert "ollama run" not in k8s_content


def test_same_day_entries_share_header():
    append("k8s", "kubectl get pods", "命令一")
    append("k8s", "kubectl apply -f", "命令二")
    content = _path("k8s").read_text(encoding="utf-8")
    from datetime import datetime
    today = datetime.now().strftime("%Y-%m-%d")
    assert content.count(f"## {today}") == 1, "Same-day entries must share one header"

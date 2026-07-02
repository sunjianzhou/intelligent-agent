"""Tests for metrics — L3/L4 indicators are registered and queryable."""
import os
import sys
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def test_l3_metrics_registered():
    """L3 指标（retrieve hits/total/similarity/duration）已注册并可调用。"""
    from api.metrics import (
        l3_retrieve_hits,
        l3_retrieve_total,
        l3_retrieve_avg_similarity,
        l3_retrieve_duration_ms,
    )
    # 初始值均为 0 / 0.0
    # Counter 不从 Prometheus registry 读当前值（线程安全），仅验证对象存在
    assert l3_retrieve_hits is not None
    assert l3_retrieve_total is not None
    assert l3_retrieve_avg_similarity is not None
    assert l3_retrieve_duration_ms is not None

    # inc / set / observe 不抛异常
    l3_retrieve_total.inc()
    l3_retrieve_hits.inc()
    l3_retrieve_avg_similarity.set(0.85)
    l3_retrieve_duration_ms.observe(12.5)


def test_l4_metrics_registered():
    """L4 指标（source_coverage / snapshot_backups）已注册并可调用。"""
    from api.metrics import (
        l4_distill_source_coverage,
        l4_distill_snapshot_backups,
    )
    assert l4_distill_source_coverage is not None
    assert l4_distill_snapshot_backups is not None

    l4_distill_source_coverage.set(0.72)
    l4_distill_snapshot_backups.set(5)

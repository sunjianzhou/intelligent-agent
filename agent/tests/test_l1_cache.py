"""Tests for L1Cache — 5 unit + 2 boundary = 7 cases."""
import os
import sys
import time
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from core.l1_cache import L1Cache


# ---------------------------------------------------------------------------
# Unit: basic get/set
# ---------------------------------------------------------------------------

def test_set_and_get():
    cache = L1Cache(ttl_seconds=30, max_entries=10)
    cache.set("key1", "response1")
    assert cache.get("key1") == "response1"


# ---------------------------------------------------------------------------
# Unit: miss returns None
# ---------------------------------------------------------------------------

def test_miss_returns_none():
    cache = L1Cache(ttl_seconds=30, max_entries=10)
    assert cache.get("nonexistent") is None


# ---------------------------------------------------------------------------
# Unit: TTL expiry
# ---------------------------------------------------------------------------

def test_ttl_expiry(monkeypatch):
    """TTL 过期后 get 返回 None。"""
    cache = L1Cache(ttl_seconds=1, max_entries=10)
    cache.set("key1", "response1")

    # 模拟时间前进 2 秒
    original_time = time.time
    fake_start = original_time()

    class FakeTime:
        def __init__(self):
            self._offset = 0

        def __call__(self):
            return fake_start + self._offset

    fake_time = FakeTime()
    monkeypatch.setattr(time, 'time', fake_time)

    # 重新 set（使用 fake time）
    cache.set("key2", "response2")
    assert cache.get("key2") == "response2"

    # 前进 2 秒（超过 1s TTL）
    fake_time._offset = 2.0
    assert cache.get("key2") is None


# ---------------------------------------------------------------------------
# Unit: LRU eviction
# ---------------------------------------------------------------------------

def test_lru_eviction():
    """超过 max_entries 时淘汰最旧条目。"""
    cache = L1Cache(ttl_seconds=300, max_entries=3)
    cache.set("a", "1")
    cache.set("b", "2")
    cache.set("c", "3")
    cache.set("d", "4")  # 触发淘汰，a 最旧

    assert cache.get("a") is None
    assert cache.get("b") == "2"
    assert cache.get("c") == "3"
    assert cache.get("d") == "4"
    assert cache.size == 3


# ---------------------------------------------------------------------------
# Unit: user isolation via snapshot
# ---------------------------------------------------------------------------

def test_invalidate_user_bumps_snapshot():
    """invalidate_user 递增快照版本号。"""
    cache = L1Cache()
    assert cache.get_snapshot("user1") == 0
    cache.invalidate_user("user1")
    assert cache.get_snapshot("user1") == 1
    cache.invalidate_user("user1")
    assert cache.get_snapshot("user1") == 2
    # 其他用户不受影响
    assert cache.get_snapshot("user2") == 0


def test_clear_removes_all():
    cache = L1Cache(ttl_seconds=30, max_entries=10)
    cache.set("a", "1")
    cache.set("b", "2")
    assert cache.size == 2
    cache.clear()
    assert cache.size == 0
    assert cache.get("a") is None
    assert cache.get("b") is None


# ---------------------------------------------------------------------------
# Boundary: TTL not expired
# ---------------------------------------------------------------------------

def test_boundary_ttl_not_expired_yet():
    """TTL 未过期时仍可命中。"""
    cache = L1Cache(ttl_seconds=300, max_entries=10)
    cache.set("key1", "response1")
    # 立即查询应命中（300s TTL 远大于 0）
    assert cache.get("key1") == "response1"


# ---------------------------------------------------------------------------
# Boundary: different keys don't collide
# ---------------------------------------------------------------------------

def test_boundary_different_keys_no_collision():
    """不同 key 不互相干扰。"""
    cache = L1Cache(ttl_seconds=30, max_entries=10)
    cache.set("key_a", "resp_a")
    cache.set("key_b", "resp_b")
    assert cache.get("key_a") == "resp_a"
    assert cache.get("key_b") == "resp_b"
    assert cache.get("key_c") is None

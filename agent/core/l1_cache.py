"""L1Cache — 精确响应缓存（prometheus风格，基于 prompt hash + TTL + LRU）。"""
from __future__ import annotations

import threading
import time
from collections import OrderedDict
from typing import Optional


class L1Cache:
    """线程安全的 L1 精确匹配响应缓存。

    缓存 key 由调用方生成（sha256 of prompt + model + persona + memory_snapshot），
    本类仅负责 get/set/invalidate 的存储层逻辑。
    TTL 过期后自动淘汰；超过 max_entries 时 LRU 淘汰最旧条目。
    """

    def __init__(self, ttl_seconds: int = 300, max_entries: int = 100) -> None:
        self._ttl = ttl_seconds
        self._max = max_entries
        self._store: OrderedDict = OrderedDict()
        self._lock = threading.Lock()
        # per-user 记忆快照版本号，写入记忆时递增，用于构造变化的 cache key
        self._memory_snapshots: dict[str, int] = {}
        self._snapshot_lock = threading.Lock()

    # ── 核心 CRUD ────────────────────────────────────────────

    def get(self, key: str) -> Optional[str]:
        """查询缓存，命中返回响应字符串，过期/未命中返回 None。"""
        with self._lock:
            entry = self._store.get(key)
            if entry is None:
                return None
            response, expire_ts = entry
            if time.time() > expire_ts:
                del self._store[key]
                return None
            self._store.move_to_end(key)
            return response

    def set(self, key: str, response: str) -> None:
        """写入缓存，自动 LRU 淘汰。"""
        expire_ts = time.time() + self._ttl
        with self._lock:
            self._store[key] = (response, expire_ts)
            self._store.move_to_end(key)
            while len(self._store) > self._max:
                self._store.popitem(last=False)

    def invalidate_user(self, user_id: str) -> None:
        """递增 user 的记忆快照版本号，使该用户的旧缓存 key 不再命中。

        内存操作轻量（仅修改一个 int），不做全量缓存清空。
        """
        with self._snapshot_lock:
            current = self._memory_snapshots.get(user_id, 0)
            self._memory_snapshots[user_id] = current + 1

    # ── 快照版本（供 cache key 构造方使用） ─────────────────

    def get_snapshot(self, user_id: str) -> int:
        """返回 user 的当前记忆快照版本号。"""
        with self._snapshot_lock:
            return self._memory_snapshots.get(user_id, 0)

    # ── 管理接口 ────────────────────────────────────────────

    @property
    def size(self) -> int:
        """当前缓存条目数。"""
        with self._lock:
            return len(self._store)

    def clear(self) -> None:
        """清空全部缓存。"""
        with self._lock:
            self._store.clear()

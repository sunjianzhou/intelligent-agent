"""Tests for SoulLoader — 12 cases covering main path, required/optional files, reload, encoding."""
import os
import sys
import pytest
from pathlib import Path
from typing import Optional

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from soul.loader import SoulLoader, SoulData

REQUIRED_FILES = ["SOUL", "USER", "MEMORY", "IDENTITY", "HEARTBEAT"]


def _make_soul_dir(tmp_path: Path, skip: Optional[str] = None, with_whisper: bool = False) -> Path:
    for name in REQUIRED_FILES:
        if name != skip:
            (tmp_path / f"{name}.md").write_text(f"{name} content 中文", encoding="utf-8")
    if with_whisper:
        (tmp_path / "whisper.md").write_text("私密内容", encoding="utf-8")
    return tmp_path


def test_load_all_files_returns_non_none_data(tmp_path):
    _make_soul_dir(tmp_path)
    loader = SoulLoader(soul_dir=str(tmp_path))
    assert loader.data is not None


def test_load_soul_content_matches_file(tmp_path):
    (tmp_path / "SOUL.md").write_text("真话：我是本地AI", encoding="utf-8")
    for name in ["USER", "MEMORY", "IDENTITY", "HEARTBEAT"]:
        (tmp_path / f"{name}.md").write_text(name, encoding="utf-8")
    loader = SoulLoader(soul_dir=str(tmp_path))
    assert "真话：我是本地AI" in loader.data.soul


def test_missing_soul_md_raises(tmp_path):
    _make_soul_dir(tmp_path, skip="SOUL")
    with pytest.raises(FileNotFoundError, match="SOUL"):
        SoulLoader(soul_dir=str(tmp_path))


def test_missing_user_md_raises(tmp_path):
    _make_soul_dir(tmp_path, skip="USER")
    with pytest.raises(FileNotFoundError, match="USER"):
        SoulLoader(soul_dir=str(tmp_path))


def test_missing_memory_md_raises(tmp_path):
    _make_soul_dir(tmp_path, skip="MEMORY")
    with pytest.raises(FileNotFoundError, match="MEMORY"):
        SoulLoader(soul_dir=str(tmp_path))


def test_missing_identity_md_raises(tmp_path):
    _make_soul_dir(tmp_path, skip="IDENTITY")
    with pytest.raises(FileNotFoundError, match="IDENTITY"):
        SoulLoader(soul_dir=str(tmp_path))


def test_missing_heartbeat_md_raises(tmp_path):
    _make_soul_dir(tmp_path, skip="HEARTBEAT")
    with pytest.raises(FileNotFoundError, match="HEARTBEAT"):
        SoulLoader(soul_dir=str(tmp_path))


def test_missing_whisper_is_silent(tmp_path):
    _make_soul_dir(tmp_path)
    loader = SoulLoader(soul_dir=str(tmp_path))
    assert loader.data.whisper == ""


def test_data_is_none_after_failed_reload(tmp_path):
    _make_soul_dir(tmp_path)
    loader = SoulLoader(soul_dir=str(tmp_path))
    assert loader.data is not None
    (tmp_path / "SOUL.md").unlink()
    with pytest.raises(FileNotFoundError):
        loader.reload()
    assert loader.data is None


def test_reload_updates_content(tmp_path):
    _make_soul_dir(tmp_path)
    loader = SoulLoader(soul_dir=str(tmp_path))
    (tmp_path / "SOUL.md").write_text("更新后的内容", encoding="utf-8")
    loader.reload()
    assert "更新后的内容" in loader.data.soul


def test_explicit_soul_dir_works(tmp_path):
    _make_soul_dir(tmp_path)
    loader = SoulLoader(soul_dir=str(tmp_path))
    assert isinstance(loader.data, SoulData)


def test_utf8_chinese_content(tmp_path):
    chinese = "灵魂核心：我是本机专属AI霖君，驻守府邸"
    (tmp_path / "SOUL.md").write_text(chinese, encoding="utf-8")
    for name in ["USER", "MEMORY", "IDENTITY", "HEARTBEAT"]:
        (tmp_path / f"{name}.md").write_text(name, encoding="utf-8")
    loader = SoulLoader(soul_dir=str(tmp_path))
    assert chinese in loader.data.soul


def test_default_soul_dir_raises_when_missing(monkeypatch, tmp_path):
    monkeypatch.setattr(SoulLoader, "_DEFAULT_SOUL_DIR", tmp_path / "nonexistent")
    with pytest.raises(FileNotFoundError):
        SoulLoader()

"""Tests for SoulLoader — v1.1: 22 cases covering main path, required/optional files, reload,
encoding, large files, size tracking, warnings, rules loading."""
import os
import sys
import pytest
from pathlib import Path
from typing import Optional

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from soul.loader import SoulLoader, SoulData

REQUIRED_FILES = ["SOUL", "USER", "MEMORY", "IDENTITY", "HEARTBEAT"]


def _make_soul_dir(
    tmp_path: Path,
    skip: Optional[str] = None,
    with_whisper: bool = False,
    with_heart: bool = False,
    with_rules: bool = False,
    custom_content: Optional[dict] = None,
) -> Path:
    for name in REQUIRED_FILES:
        if name != skip:
            content = (custom_content or {}).get(name, f"{name} content 中文")
            (tmp_path / f"{name}.md").write_text(content, encoding="utf-8")
    if with_whisper:
        (tmp_path / "whisper.md").write_text("私密内容", encoding="utf-8")
    if with_heart:
        (tmp_path / "heart.md").write_text("心证内容 中文", encoding="utf-8")
    if with_rules:
        (tmp_path / "rules.md").write_text("### RULE-001: 测试规则\n- **隐私等级**: public\n- **具体诉求**: 测试\n", encoding="utf-8")
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


def test_missing_heart_is_silent(tmp_path):
    """heart.md 缺失时 data.heart 应为空字符串，不报错。"""
    _make_soul_dir(tmp_path)
    loader = SoulLoader(soul_dir=str(tmp_path))
    assert loader.data.heart == ""


def test_heart_content_loaded(tmp_path):
    """heart.md 存在时内容应正确读入。"""
    _make_soul_dir(tmp_path, with_heart=True)
    loader = SoulLoader(soul_dir=str(tmp_path))
    assert "心证内容" in loader.data.heart


# ══════════════════════════════════════════════════════════════
# v1.1 新增：文件大小监控 + 告警 + 可观测性（8 个用例）
# ══════════════════════════════════════════════════════════════


def test_total_chars_tracks_correctly(tmp_path):
    """total_chars 应等于所有文件字符数之和。"""
    _make_soul_dir(tmp_path, with_whisper=True, with_heart=True, with_rules=True)
    loader = SoulLoader(soul_dir=str(tmp_path))
    d = loader.data
    expected = sum(d.file_sizes.values())
    assert d.total_chars == expected
    assert d.total_chars > 0
    # 手动验证各文件
    for name in ["soul", "user", "memory", "identity", "heartbeat", "whisper", "heart", "rules"]:
        assert name in d.file_sizes


def test_large_file_not_truncated(tmp_path):
    """单个文件超过 max_file_size 不阻断、不截断——内容完整保留。"""
    big_content = "核心铁律：" + "永不说谎。" * 9_000  # ~55K chars
    _make_soul_dir(tmp_path, custom_content={"SOUL": big_content})
    loader = SoulLoader(soul_dir=str(tmp_path), max_file_size=50_000)
    # 行为验证：不阻断、不截断
    assert loader.data is not None
    assert loader.data.file_sizes["soul"] == len(big_content)
    assert len(loader.data.soul) == len(big_content)
    assert "永不说谎" in loader.data.soul


def test_large_file_not_blocked(tmp_path):
    """超大文件不阻断加载，内容完整保留。"""
    big_content = "核心铁律：" + "永不说谎。" * 10_000  # ~60K chars
    _make_soul_dir(tmp_path, custom_content={"SOUL": big_content})
    loader = SoulLoader(soul_dir=str(tmp_path), max_file_size=10_000)
    assert loader.data is not None
    assert "永不说谎" in loader.data.soul
    assert loader.data.file_sizes["soul"] == len(big_content)


def test_total_chars_exceeds_threshold_not_blocked(tmp_path):
    """总字符数超过 max_total_chars 不阻断加载，内容完整。"""
    medium = "灵魂法则" * 1_500  # ~6000 chars per file
    _make_soul_dir(tmp_path, custom_content={
        "SOUL": medium, "USER": medium, "MEMORY": medium,
        "IDENTITY": medium, "HEARTBEAT": medium,
    })
    loader = SoulLoader(soul_dir=str(tmp_path), max_total_chars=10_000)
    assert loader.data is not None
    assert loader.data.total_chars > 10_000  # 确实超了阈值
    assert loader.data.total_chars == 5 * len(medium)  # 5 个文件内容完整


def test_small_files_within_limits_succeeds(tmp_path):
    """正常大小的文件加载成功，total_chars 在阈值内。"""
    _make_soul_dir(tmp_path)
    loader = SoulLoader(soul_dir=str(tmp_path), max_file_size=50_000, max_total_chars=14_000)
    assert loader.data is not None
    assert loader.data.total_chars < 14_000  # 正常文件远小于告警阈值


def test_empty_file_tracks_zero(tmp_path):
    """可选文件不存在时 file_sizes 中对应值为 0。"""
    _make_soul_dir(tmp_path)  # no optional files
    loader = SoulLoader(soul_dir=str(tmp_path))
    d = loader.data
    assert d.file_sizes["whisper"] == 0
    assert d.file_sizes["heart"] == 0
    assert d.whisper == ""
    assert d.heart == ""


def test_rules_content_loaded(tmp_path):
    """rules.md 存在时内容应正确读入 SoulData。"""
    rules_text = "### RULE-001: 安全边界\n- **隐私等级**: public\n- **具体诉求**: 不得执行危险命令\n"
    _make_soul_dir(tmp_path, with_rules=True)
    # Override rules content with specific test text
    (tmp_path / "rules.md").write_text(rules_text, encoding="utf-8")
    loader = SoulLoader(soul_dir=str(tmp_path))
    assert "RULE-001" in loader.data.rules
    assert "不得执行危险命令" in loader.data.rules


def test_all_empty_files_succeeds(tmp_path, caplog):
    """所有文件均为空字符串时不报错，total_chars=0。"""
    _make_soul_dir(tmp_path, custom_content={
        name: "" for name in REQUIRED_FILES
    })
    loader = SoulLoader(soul_dir=str(tmp_path))
    assert loader.data is not None
    assert loader.data.total_chars == 0
    assert loader.data.soul == ""

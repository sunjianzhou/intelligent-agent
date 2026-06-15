"""Unit tests for client/main.py (_load_config, _resolve_data_dir)."""
import sys
from pathlib import Path
from unittest.mock import patch

import pytest

import main as main_mod
from main import _load_config, _resolve_data_dir


# ── _load_config ──────────────────────────────────────────────────────────────

def test_load_config_returns_empty_when_no_file(tmp_path, monkeypatch):
    monkeypatch.setattr(main_mod, "_CONFIG_FILE", tmp_path / "missing.yaml")
    assert _load_config() == {}


def test_load_config_parses_valid_yaml(tmp_path, monkeypatch):
    cfg_file = tmp_path / "config.yaml"
    cfg_file.write_text("server:\n  url: http://example.com\n", encoding="utf-8")
    monkeypatch.setattr(main_mod, "_CONFIG_FILE", cfg_file)
    result = _load_config()
    assert result["server"]["url"] == "http://example.com"


def test_load_config_empty_yaml_returns_empty(tmp_path, monkeypatch):
    cfg_file = tmp_path / "config.yaml"
    cfg_file.write_text("", encoding="utf-8")
    monkeypatch.setattr(main_mod, "_CONFIG_FILE", cfg_file)
    assert _load_config() == {}


def test_load_config_bad_yaml_returns_empty(tmp_path, monkeypatch, capsys):
    cfg_file = tmp_path / "config.yaml"
    cfg_file.write_text(": invalid: yaml: [\n", encoding="utf-8")
    monkeypatch.setattr(main_mod, "_CONFIG_FILE", cfg_file)
    result = _load_config()
    assert result == {}
    # Should print a warning to stderr
    err = capsys.readouterr().err
    assert "warn" in err.lower() or "failed" in err.lower()


def test_load_config_no_yaml_import_returns_empty(tmp_path, monkeypatch):
    cfg_file = tmp_path / "config.yaml"
    cfg_file.write_text("key: value\n", encoding="utf-8")
    monkeypatch.setattr(main_mod, "_CONFIG_FILE", cfg_file)
    with patch.dict(sys.modules, {"yaml": None}):
        result = _load_config()
    # With yaml unavailable ImportError is caught → returns {}
    assert result == {}


# ── _resolve_data_dir ─────────────────────────────────────────────────────────

def test_resolve_data_dir_default_when_no_config():
    result = _resolve_data_dir({})
    # Default is "./datas", resolved relative to main.py's directory
    expected = Path(main_mod.__file__).parent / "./datas"
    assert Path(result) == expected.resolve() or result.endswith("datas")


def test_resolve_data_dir_relative_resolved_to_main_dir():
    cfg = {"data": {"dir": "my_sessions"}}
    result = _resolve_data_dir(cfg)
    main_dir = Path(main_mod.__file__).parent
    assert Path(result) == main_dir / "my_sessions"


def test_resolve_data_dir_absolute_unchanged():
    abs_path = "C:/absolute/path" if sys.platform == "win32" else "/absolute/path"
    cfg = {"data": {"dir": abs_path}}
    result = _resolve_data_dir(cfg)
    # Compare via Path to handle OS-specific separators
    assert Path(result) == Path(abs_path)

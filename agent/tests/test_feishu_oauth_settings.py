"""Tests for Feishu OAuth settings fields (Task 1)."""
import os
import pytest


def test_settings_has_feishu_oauth_redirect_uri():
    from config.settings import Settings
    s = Settings(
        feishu_oauth_redirect_uri="https://example.com/feishu/oauth/callback",
        feishu_oauth_encryption_key="dGVzdGtleXRlc3RrZXl0ZXN0a2V5dGVzdA==",
    )
    assert s.feishu_oauth_redirect_uri == "https://example.com/feishu/oauth/callback"


def test_settings_feishu_oauth_defaults_empty():
    from config.settings import Settings
    s = Settings()
    assert s.feishu_oauth_redirect_uri == ""
    assert s.feishu_oauth_encryption_key == ""

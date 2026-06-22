"""Tests for ImageGenerationTool auto-reading chat-attached image via ContextVar."""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from core._context_vars import _request_image_b64_ctx
from tools.builtin_tools.image_tool import ImageGenerationTool


@pytest.mark.asyncio
async def test_execute_async_uses_context_image_for_img2img():
    token = _request_image_b64_ctx.set("ZmFrZWJhc2U2NA==")
    try:
        tool = ImageGenerationTool()
        fake_provider = MagicMock()
        fake_provider.is_available.return_value = True
        fake_result = MagicMock(success=True, image_bytes=b"img", image_url="", provider="sd_webui", model="sd15")
        fake_provider.generate = AsyncMock(return_value=fake_result)

        with patch("services.image.create_image_provider", return_value=fake_provider), \
             patch("tools.builtin_tools.image_tool._persist_image", new=AsyncMock(return_value="/api/images/x.png")):
            await tool.execute_async(prompt="a cat, watercolor", sampler_name="euler", denoising_strength=0.5)

        sent_req = fake_provider.generate.call_args[0][0]
        assert sent_req.init_image_base64 == "ZmFrZWJhc2U2NA=="
        assert sent_req.sampler_name == "euler"
        assert sent_req.denoising_strength == 0.5
    finally:
        _request_image_b64_ctx.reset(token)


@pytest.mark.asyncio
async def test_execute_async_without_context_image_is_txt2img():
    token = _request_image_b64_ctx.set(None)
    try:
        tool = ImageGenerationTool()
        fake_provider = MagicMock()
        fake_provider.is_available.return_value = True
        fake_result = MagicMock(success=True, image_bytes=b"img", image_url="", provider="sd_webui", model="sd15")
        fake_provider.generate = AsyncMock(return_value=fake_result)

        with patch("services.image.create_image_provider", return_value=fake_provider), \
             patch("tools.builtin_tools.image_tool._persist_image", new=AsyncMock(return_value="/api/images/x.png")):
            await tool.execute_async(prompt="a cat")

        sent_req = fake_provider.generate.call_args[0][0]
        assert sent_req.init_image_base64 is None
    finally:
        _request_image_b64_ctx.reset(token)

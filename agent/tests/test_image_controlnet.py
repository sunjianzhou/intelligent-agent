"""Tests for ControlNet support in ImageRequest / SDWebUIProvider / image_router."""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from services.image.base_image_provider import ImageRequest


def test_image_request_controlnet_defaults():
    req = ImageRequest(prompt="a cat")
    assert req.controlnet_enabled is False
    assert req.controlnet_module == "none"
    assert req.controlnet_model == ""
    assert req.controlnet_weight == 1.0


def test_image_request_controlnet_explicit():
    req = ImageRequest(
        prompt="a cat",
        controlnet_enabled=True,
        controlnet_module="canny",
        controlnet_model="control_v11p_sd15_canny",
        controlnet_weight=0.8,
    )
    assert req.controlnet_enabled is True
    assert req.controlnet_module == "canny"
    assert req.controlnet_model == "control_v11p_sd15_canny"
    assert req.controlnet_weight == 0.8


import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from services.image.sd_webui_provider import SDWebUIProvider


def _make_provider():
    return SDWebUIProvider(base_url="http://localhost:7860", model="sd15")


@pytest.mark.asyncio
async def test_list_controlnet_modules_success():
    provider = _make_provider()
    fake_resp = MagicMock()
    fake_resp.json.return_value = {"module_list": ["none", "canny", "depth"]}
    fake_resp.raise_for_status = MagicMock()

    mock_client = AsyncMock()
    mock_client.get = AsyncMock(return_value=fake_resp)
    mock_client.__aenter__.return_value = mock_client
    mock_client.__aexit__.return_value = False

    with patch("httpx.AsyncClient", return_value=mock_client):
        result = await provider.list_controlnet_modules()

    assert result == ["none", "canny", "depth"]
    mock_client.get.assert_called_once_with("http://localhost:7860/controlnet/module_list")


@pytest.mark.asyncio
async def test_list_controlnet_modules_failure_returns_empty():
    provider = _make_provider()
    with patch("httpx.AsyncClient", side_effect=RuntimeError("boom")):
        result = await provider.list_controlnet_modules()
    assert result == []


@pytest.mark.asyncio
async def test_list_controlnet_models_success():
    provider = _make_provider()
    fake_resp = MagicMock()
    fake_resp.json.return_value = {"model_list": ["control_v11p_sd15_canny"]}
    fake_resp.raise_for_status = MagicMock()

    mock_client = AsyncMock()
    mock_client.get = AsyncMock(return_value=fake_resp)
    mock_client.__aenter__.return_value = mock_client
    mock_client.__aexit__.return_value = False

    with patch("httpx.AsyncClient", return_value=mock_client):
        result = await provider.list_controlnet_models()

    assert result == ["control_v11p_sd15_canny"]
    mock_client.get.assert_called_once_with("http://localhost:7860/controlnet/model_list")


@pytest.mark.asyncio
async def test_generate_injects_controlnet_payload_when_enabled():
    from services.image.base_image_provider import ImageRequest
    import base64

    provider = _make_provider()
    req = ImageRequest(
        prompt="a cat",
        init_image_base64="ZmFrZWJhc2U2NA==",
        controlnet_enabled=True,
        controlnet_module="canny",
        controlnet_model="control_v11p_sd15_canny",
        controlnet_weight=0.8,
    )

    fake_resp = MagicMock()
    fake_resp.json.return_value = {"images": [base64.b64encode(b"img").decode()]}
    fake_resp.raise_for_status = MagicMock()

    mock_client = AsyncMock()
    mock_client.post = AsyncMock(return_value=fake_resp)
    mock_client.__aenter__.return_value = mock_client
    mock_client.__aexit__.return_value = False

    with patch("httpx.AsyncClient", return_value=mock_client):
        result = await provider.generate(req)

    assert result.success is True
    sent_kwargs = mock_client.post.call_args
    sent_payload = sent_kwargs.kwargs.get("json") or sent_kwargs[1].get("json")
    assert sent_payload["alwayson_scripts"]["controlnet"]["args"][0] == {
        "input_image": "ZmFrZWJhc2U2NA==",
        "module": "canny",
        "model": "control_v11p_sd15_canny",
        "weight": 0.8,
    }


@pytest.mark.asyncio
async def test_generate_omits_controlnet_payload_when_disabled():
    from services.image.base_image_provider import ImageRequest
    import base64

    provider = _make_provider()
    req = ImageRequest(prompt="a cat", init_image_base64="ZmFrZWJhc2U2NA==")

    fake_resp = MagicMock()
    fake_resp.json.return_value = {"images": [base64.b64encode(b"img").decode()]}
    fake_resp.raise_for_status = MagicMock()

    mock_client = AsyncMock()
    mock_client.post = AsyncMock(return_value=fake_resp)
    mock_client.__aenter__.return_value = mock_client
    mock_client.__aexit__.return_value = False

    with patch("httpx.AsyncClient", return_value=mock_client):
        result = await provider.generate(req)

    assert result.success is True
    sent_kwargs = mock_client.post.call_args
    sent_payload = sent_kwargs.kwargs.get("json") or sent_kwargs[1].get("json")
    assert "alwayson_scripts" not in sent_payload

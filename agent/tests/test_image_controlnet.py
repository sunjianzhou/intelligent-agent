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

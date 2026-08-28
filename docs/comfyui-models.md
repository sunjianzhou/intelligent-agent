# ComfyUI 免费模型下载引导（HuggingFace / ModelScope）

> R-08 后续：免费高性能模型一键下载引导。所有模型均可在本地免费推理；
> 放到 ComfyUI 对应目录后重启（或刷新）ComfyUI，即可在「图片生成」页的模型列表中选择。

## 下载方式

任选一种：

```bash
# HuggingFace CLI（需先 pip install -U "huggingface_hub[cli]"）
hf download <repo> <file> --local-dir <ComfyUI>/models/<目录>

# 或 wget 直链（ModelScope 国内可达性通常更好）
wget -P <ComfyUI>/models/<目录> <直链>
```

## 推荐模型表

| 模型 | 类型 | 目录 | HuggingFace | ModelScope |
|------|------|------|-------------|------------|
| FLUX.1 schnell | 基础模型 | `models/unet/` | [black-forest-labs/FLUX.1-schnell](https://huggingface.co/black-forest-labs/FLUX.1-schnell) | [AI-ModelScope/FLUX.1-schnell](https://modelscope.cn/models/AI-ModelScope/FLUX.1-schnell) |
| Qwen-Image | 基础模型 | `models/unet/` | [Qwen/Qwen-Image](https://huggingface.co/Qwen/Qwen-Image) | [Qwen/Qwen-Image](https://modelscope.cn/models/Qwen/Qwen-Image) |
| HiDream-I1 | 基础模型 | `models/diffusion_models/` | [HiDream-Org/HiDream-I1](https://huggingface.co/HiDream-Org/HiDream-I1) | [HiDream/HiDream-I1](https://modelscope.cn/models/HiDream/HiDream-I1) |
| SDXL base 1.0 | 基础模型 | `models/checkpoints/` | [stabilityai/stable-diffusion-xl-base-1.0](https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0) | [AI-ModelScope/stable-diffusion-xl-base-1.0](https://modelscope.cn/models/AI-ModelScope/stable-diffusion-xl-base-1.0) |
| SD 1.5 | 基础模型 | `models/checkpoints/` | [runwayml/stable-diffusion-v1-5](https://huggingface.co/runwayml/stable-diffusion-v1-5) | [AI-ModelScope/stable-diffusion-v1-5](https://modelscope.cn/models/AI-ModelScope/stable-diffusion-v1-5) |
| ControlNet Canny (SD1.5) | ControlNet | `models/controlnet/` | [lllyasviel/ControlNet-v1-1](https://huggingface.co/lllyasviel/ControlNet-v1-1) | [licyks/ControlNet-v1-1](https://modelscope.cn/models/licyks/ControlNet-v1-1) |
| ControlNet Canny (SDXL) | ControlNet | `models/controlnet/` | [diffusers/controlnet-canny-sdxl-1.0](https://huggingface.co/diffusers/controlnet-canny-sdxl-1.0) | — |
| t5xxl + ae（FLUX 配套） | CLIP/VAE | `models/clip/` + `models/vae/` | [comfyanonymous/flux_text_encoders](https://huggingface.co/comfyanonymous/flux_text_encoders) | — |

## ControlNet / 局部重绘说明

- 本项目的 ControlNet 预设与局部重绘（inpaint）目前支持 **SD1.5 / SDXL** 模板：
  - ControlNet：在「图片生成」页选择 ControlNet 模型 + 上传参考图（强度可调）；
  - 局部重绘：先上传底图（img2img），再上传白色区域蒙版，配合去噪强度控制重绘范围。
- FLUX / Qwen / SD3.5 模板暂未接入 ControlNet/蒙版（模型差异大，后续按需扩展）。

## 校验

```bash
ls <ComfyUI>/models/controlnet/   # ControlNet 应能看到 control_*.safetensors
curl http://localhost:8188/object_info | grep -o 'control_[a-z0-9_.]*\.safetensors' | sort -u
```

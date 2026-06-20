#!/bin/bash
set -e
cd /app

MARKER=/app/.deps_installed_docker

# 国内网络下 pypi.org/files.pythonhosted.org 经常因 IPv6 解析问题连不上，默认走清华源
export PIP_INDEX_URL="${PIP_INDEX_URL:-https://pypi.tuna.tsinghua.edu.cn/simple/}"
export PIP_TRUSTED_HOST="${PIP_TRUSTED_HOST:-pypi.tuna.tsinghua.edu.cn}"

if [ ! -f "$MARKER" ]; then
    echo "[comfyui] first run inside container, installing python deps (this can take a while)..."
    pip install --no-cache-dir --upgrade pip
    # 与宿主机 conda 环境（ai-agent）保持一致的 torch/cuda 版本，避免 requirements.txt 拉取不匹配的版本
    pip install --no-cache-dir torch==2.5.1 torchvision==0.20.1 torchaudio==2.5.1
    pip install --no-cache-dir -r requirements.txt
    for req in custom_nodes/*/requirements.txt; do
        [ -f "$req" ] && pip install --no-cache-dir -r "$req" || true
    done
    touch "$MARKER"
else
    echo "[comfyui] deps already installed, skipping (delete $MARKER to force reinstall)"
fi

exec python main.py --listen 0.0.0.0 --port 8188 "$@"

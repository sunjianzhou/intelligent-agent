FROM python:3.11-slim

WORKDIR /app

# 切换 apt 镜像为清华源（Debian Bookworm，sources.list.d/debian.sources）
RUN sed -i 's/deb.debian.org/mirrors.tuna.tsinghua.edu.cn/g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update && apt-get install -y --no-install-recommends \
        curl build-essential tzdata \
    && rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Shanghai

# ── 可选：GitHub MCP（Node.js + 包）────────────────────────────
# 默认不安装以节省 ~200 MB 镜像体积；如需启用：
#   docker-compose build --build-arg INSTALL_MCP=1 agent
ARG INSTALL_MCP=0
RUN if [ "$INSTALL_MCP" = "1" ]; then \
        apt-get update && apt-get install -y --no-install-recommends ca-certificates gnupg \
        && curl -fsSL https://deb.nodesource.com/setup_18.x | bash - \
        && apt-get install -y nodejs \
        && npm install -g @modelcontextprotocol/server-github \
        && apt-get clean \
        && rm -rf /var/lib/apt/lists/*; \
    fi

# Python 依赖：requirements-docker.txt 比 requirements.txt 更精简
COPY requirements-docker.txt .
# 先装 CPU-only torch（避免 GPU 版引入 nvidia-cublas 等 CUDA 包，绕过镜像哈希冲突）
RUN pip install --no-cache-dir \
    --index-url https://download.pytorch.org/whl/cpu \
    "torch>=2.0.0,<3.0.0"
# 再装其余依赖（torch 已满足 sentence-transformers 要求，不会重装）
RUN pip install --no-cache-dir \
    -i https://mirrors.aliyun.com/pypi/simple/ \
    --trusted-host mirrors.aliyun.com \
    -r requirements-docker.txt

COPY . .

EXPOSE 8000

# 非 root 用户运行（提升容器安全性）
# 预建运行时数据目录，确保 appuser 有写权限（卷挂载会覆盖 /app/data，需提前创建并赋权）
RUN useradd --create-home --uid 1000 appuser \
    && mkdir -p /app/data/feedback /app/chroma_data \
    && chown -R appuser:appuser /app
USER appuser

CMD ["python", "-m", "uvicorn", "api.fastapi_app:app", \
     "--host", "0.0.0.0", "--port", "8000"]

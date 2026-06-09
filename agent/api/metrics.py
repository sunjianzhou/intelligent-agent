"""Prometheus 指标定义与 /metrics 端点。"""
import time
from prometheus_client import (
    Counter, Histogram, Gauge,
    generate_latest, CONTENT_TYPE_LATEST,
)
from fastapi import Request, Response
from fastapi.routing import APIRouter

# ── 指标定义 ───────────────────────────────────────────────────

# 请求总量（按端点 + 方法 + 状态码）
http_requests_total = Counter(
    "http_requests_total",
    "Total HTTP requests",
    ["method", "endpoint", "status_code"],
)

# 请求延迟分布（单位：秒）
http_request_duration_seconds = Histogram(
    "http_request_duration_seconds",
    "HTTP request latency",
    ["method", "endpoint"],
    buckets=[0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0],
)

# LLM 推理计数与延迟
llm_inference_total = Counter(
    "llm_inference_total",
    "Total LLM inference calls",
    ["model", "outcome"],   # outcome: success | timeout | error
)
llm_inference_duration_seconds = Histogram(
    "llm_inference_duration_seconds",
    "LLM inference latency",
    ["model"],
    buckets=[0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 60.0, 120.0],
)

# 工具调用计数
tool_calls_total = Counter(
    "tool_calls_total",
    "Total tool invocations",
    ["tool_name", "outcome"],   # outcome: success | error
)

# 缓存命中/未命中
cache_hits_total = Counter(
    "cache_hits_total",
    "Cache hits",
    ["level"],   # level: L1 | L2
)
cache_misses_total = Counter(
    "cache_misses_total",
    "Cache misses",
    ["level"],
)

# 并发推理槽位（当前占用数）
inference_active = Gauge(
    "inference_active_requests",
    "Number of requests currently in inference",
)

# 记忆操作
memory_store_total = Counter(
    "memory_store_total",
    "Total memory store operations",
    ["memory_type"],
)
memory_retrieve_total = Counter(
    "memory_retrieve_total",
    "Total memory retrieve operations",
    ["memory_type"],
)

# 调度器任务
scheduler_tasks_executed_total = Counter(
    "scheduler_tasks_executed_total",
    "Total scheduled tasks executed",
    ["outcome"],  # outcome: success | failed
)


# ── 中间件 ────────────────────────────────────────────────────

async def metrics_middleware(request: Request, call_next):
    """记录每个 HTTP 请求的延迟和状态码。"""
    # 跳过 /metrics 自身，避免无限递归计数
    if request.url.path == "/metrics":
        return await call_next(request)

    start = time.perf_counter()
    response = await call_next(request)
    duration = time.perf_counter() - start

    endpoint = request.url.path
    method   = request.method
    status   = str(response.status_code)

    http_requests_total.labels(method=method, endpoint=endpoint, status_code=status).inc()
    http_request_duration_seconds.labels(method=method, endpoint=endpoint).observe(duration)

    return response


# ── /metrics 端点 ──────────────────────────────────────────────

metrics_router = APIRouter()

@metrics_router.get("/metrics", include_in_schema=False)
async def metrics_endpoint():
    """暴露 Prometheus 格式的指标数据。"""
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

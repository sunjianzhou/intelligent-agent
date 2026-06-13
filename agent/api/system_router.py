"""系统资源监控 API（/api/system/resources）+ 图片文件服务。"""
import asyncio
import re
from datetime import datetime
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response
from loguru import logger

from config.settings import settings

router = APIRouter()

_IMAGE_DIR = Path(__file__).parent.parent / "data" / "images"


@router.get("/api/system/resources")
async def get_system_resources():
    import psutil

    def _collect_cpu():
        overall  = psutil.cpu_percent(interval=0.2, percpu=False)
        per_core = psutil.cpu_percent(interval=0.1, percpu=True)
        return overall, per_core

    loop = asyncio.get_running_loop()
    cpu_percent, cpu_per_core = await loop.run_in_executor(None, _collect_cpu)
    cpu_count = psutil.cpu_count(logical=True)
    mem = psutil.virtual_memory()

    result = {
        "cpu_percent":    round(cpu_percent, 1),
        "cpu_count":      cpu_count,
        "cpu_used_cores": round(cpu_percent / 100 * cpu_count, 2),
        "cpu_per_core":   [round(x, 1) for x in cpu_per_core],
        "memory_percent": round(mem.percent, 1),
        "memory_used_gb": round(mem.used  / 1024 ** 3, 2),
        "memory_total_gb":round(mem.total / 1024 ** 3, 2),
        "memory_avail_gb":round(mem.available / 1024 ** 3, 2),
        "processes": {},
        "disks": [],
        "ollama_models": [],
        "gpu": None,
        "timestamp": datetime.now().isoformat(),
        "ollama_num_ctx": settings.ollama_num_ctx,
    }

    process_keywords = {
        "ollama":  "Ollama",
        "uvicorn": "Python Agent",
        "java":    "Java 后端",
        "node":    "前端(Node)",
        "vite":    "前端(Vite)",
    }
    found: dict = {}
    other_by_name: dict = {}
    try:
        for proc in psutil.process_iter(["pid", "name", "memory_info", "cmdline"]):
            try:
                pname   = (proc.info["name"] or "").lower()
                cmdline = " ".join(proc.info["cmdline"] or []).lower()
                mem_mb  = round(proc.info["memory_info"].rss / 1024 ** 2, 1)
                matched = False
                for kw, label in process_keywords.items():
                    if kw in pname or kw in cmdline:
                        if label not in found:
                            found[label] = {"mem_mb": 0, "pids": []}
                        found[label]["mem_mb"] = round(found[label]["mem_mb"] + mem_mb, 1)
                        found[label]["pids"].append(proc.info["pid"])
                        matched = True
                        break
                if not matched and mem_mb >= 5:
                    display = proc.info["name"] or "unknown"
                    if display not in other_by_name:
                        other_by_name[display] = {"mem_mb": 0.0, "count": 0}
                    other_by_name[display]["mem_mb"] = round(other_by_name[display]["mem_mb"] + mem_mb, 1)
                    other_by_name[display]["count"] += 1
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
    except Exception as e:
        result["process_error"] = str(e)

    top_others = sorted(other_by_name.items(), key=lambda x: x[1]["mem_mb"], reverse=True)[:10]
    result["top_other_processes"] = [
        {"name": name, "mem_mb": info["mem_mb"], "count": info["count"]}
        for name, info in top_others
    ]
    agent_total_mb = sum(v["mem_mb"] for v in found.values())
    sys_used_mb    = round(result.get("memory_used_gb", 0) * 1024, 1)
    other_real_mb  = max(0.0, round(sys_used_mb - agent_total_mb, 1))
    result["processes"] = {
        label: {"mem_mb": info["mem_mb"], "pid_count": len(info["pids"])}
        for label, info in found.items()
    }
    result["processes"]["其他进程"] = {"mem_mb": other_real_mb, "pid_count": -1}
    result["agent_total_mb"] = agent_total_mb
    result["other_total_mb"] = other_real_mb

    try:
        import platform as _platform
        _is_windows = _platform.system() == "Windows"
        disks = []
        seen_devices: set = set()
        for part in psutil.disk_partitions(all=False):
            if part.device in seen_devices:
                continue
            mp = part.mountpoint
            if _is_windows:
                if mp not in ("C:\\", "D:\\", "E:\\", "F:\\", "G:\\") and not mp.endswith(":\\"):
                    continue
            else:
                skip_prefixes = ("/proc", "/sys", "/dev", "/run", "/snap", "/etc/")
                skip_fs = {"tmpfs", "devtmpfs", "sysfs", "proc", "overlay", "nsfs", "cgroup"}
                if any(mp.startswith(p) for p in skip_prefixes):
                    continue
                if part.fstype in skip_fs:
                    continue
                import os as _os
                if not _os.path.isdir(mp):
                    continue
            try:
                usage = psutil.disk_usage(mp)
                seen_devices.add(part.device)
                disks.append({
                    "mountpoint": mp,
                    "total_gb":   round(usage.total / 1024 ** 3, 1),
                    "used_gb":    round(usage.used  / 1024 ** 3, 1),
                    "free_gb":    round(usage.free  / 1024 ** 3, 1),
                    "percent":    usage.percent,
                })
            except Exception:
                continue
        result["disks"] = disks
    except Exception as e:
        result["disk_error"] = str(e)

    try:
        import requests as _req
        _ollama_url = (settings.ollama_base_url or "http://localhost:11434").rstrip("/")
        r = _req.get(f"{_ollama_url}/api/ps", timeout=3)
        if r.status_code == 200:
            result["ollama_models"] = [
                {
                    "name":       m.get("name", ""),
                    "size_gb":    round(m.get("size", 0) / 1024 ** 3, 2),
                    "vram_gb":    round(m.get("size_vram", 0) / 1024 ** 3, 2),
                    "expires_at": m.get("expires_at", ""),
                }
                for m in r.json().get("models", [])
            ]
    except Exception:
        result["ollama_models"] = []

    try:
        import pynvml
        pynvml.nvmlInit()
        handle   = pynvml.nvmlDeviceGetHandleByIndex(0)
        gpu_util = pynvml.nvmlDeviceGetUtilizationRates(handle)
        gpu_mem  = pynvml.nvmlDeviceGetMemoryInfo(handle)
        gpu_temp = pynvml.nvmlDeviceGetTemperature(handle, pynvml.NVML_TEMPERATURE_GPU)
        gpu_name = pynvml.nvmlDeviceGetName(handle)
        pynvml.nvmlShutdown()
        result["gpu"] = {
            "name":          gpu_name if isinstance(gpu_name, str) else gpu_name.decode(),
            "util_percent":  gpu_util.gpu,
            "mem_used_mb":   round(gpu_mem.used  / 1024 ** 2),
            "mem_total_mb":  round(gpu_mem.total / 1024 ** 2),
            "mem_percent":   round(gpu_mem.used / gpu_mem.total * 100, 1),
            "temperature":   gpu_temp,
        }
    except Exception as e:
        result["gpu_error"] = str(e)

    return result


@router.get("/api/images/{filename}")
async def serve_generated_image(filename: str):
    if not re.match(r"^[\w\-]+\.(png|jpg|jpeg|webp)$", filename, re.IGNORECASE):
        raise HTTPException(status_code=400, detail="非法文件名")
    img_path = _IMAGE_DIR / filename
    if not img_path.exists():
        raise HTTPException(status_code=404, detail="图片不存在")
    suffix = img_path.suffix.lower()
    media_type = {"png": "image/png", "jpg": "image/jpeg",
                  "jpeg": "image/jpeg", "webp": "image/webp"}.get(suffix.lstrip("."), "image/png")
    return Response(content=img_path.read_bytes(), media_type=media_type)

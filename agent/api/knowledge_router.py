"""
知识文件入库 API — 上传文件，解析分块后存入长期记忆（ChromaDB）。

端点：
  POST   /api/knowledge/upload          — 上传文件，解析并写入知识库
  GET    /api/knowledge/files           — 列出已入库文件（含分块数/字节数）
  DELETE /api/knowledge/files/{file_id} — 删除文件及其所有向量块
"""
from __future__ import annotations

import json
import os
import re
import uuid
from datetime import datetime
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, File, Form, Request, UploadFile
from fastapi.responses import JSONResponse
from loguru import logger

import api.state as _state

router = APIRouter()

_KF_BASE     = os.path.join(os.path.dirname(__file__), "..", "data", "knowledge_files")
_CHUNK_SIZE  = 800
_CHUNK_OVERLAP = 100
_MAX_BYTES   = 10 * 1024 * 1024  # 10 MB
_ALLOWED_EXT = {".txt", ".md", ".pdf", ".json"}


# ── 文件清单辅助 ──────────────────────────────────────────────────────────────

def _manifest_path(user_id: str) -> str:
    os.makedirs(_KF_BASE, exist_ok=True)
    return os.path.join(_KF_BASE, f"{user_id}.json")


def _load_manifest(user_id: str) -> Dict[str, Any]:
    path = _manifest_path(user_id)
    if not os.path.exists(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        logger.warning(f"读取文件清单失败 [{user_id}]: {e}")
        return {}


def _save_manifest(user_id: str, manifest: Dict[str, Any]) -> None:
    with open(_manifest_path(user_id), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)


# ── 文本提取 ──────────────────────────────────────────────────────────────────

def _extract_text(filename: str, content: bytes) -> str:
    ext = os.path.splitext(filename)[1].lower()
    if ext in (".txt", ".md"):
        return content.decode("utf-8", errors="replace")
    if ext == ".json":
        try:
            data = json.loads(content.decode("utf-8", errors="replace"))
            return json.dumps(data, ensure_ascii=False, indent=2)
        except Exception:
            return content.decode("utf-8", errors="replace")
    if ext == ".pdf":
        try:
            import io
            from pypdf import PdfReader
            reader = PdfReader(io.BytesIO(content))
            pages = [page.extract_text() or "" for page in reader.pages]
            return "\n\n".join(pages)
        except ImportError:
            raise ValueError("PDF 解析需要安装 pypdf: pip install pypdf")
        except Exception as e:
            raise ValueError(f"PDF 解析失败: {e}")
    raise ValueError(f"不支持的文件类型 {ext}，支持: {', '.join(sorted(_ALLOWED_EXT))}")


# ── 分块 ─────────────────────────────────────────────────────────────────────

def _chunk_text(text: str) -> List[str]:
    """按段落/句子边界分块，保证语义完整性。"""
    text = text.strip()
    if not text:
        return []

    # 按双换行分段落
    paragraphs = [p.strip() for p in re.split(r'\n{2,}', text) if p.strip()]

    chunks: List[str] = []
    current = ""

    for para in paragraphs:
        if not current:
            current = para
        elif len(current) + len(para) + 2 <= _CHUNK_SIZE:
            current = current + "\n\n" + para
        else:
            chunks.append(current)
            # 段落本身超限则按句子继续拆分
            if len(para) > _CHUNK_SIZE:
                sentences = re.split(r'(?<=[。！？.!?…])\s*', para)
                buf = ""
                for sen in sentences:
                    if not buf:
                        buf = sen
                    elif len(buf) + len(sen) <= _CHUNK_SIZE:
                        buf += sen
                    else:
                        chunks.append(buf)
                        buf = sen
                current = buf
            else:
                current = para

    if current:
        chunks.append(current)

    return chunks


# ── 端点 ─────────────────────────────────────────────────────────────────────

@router.post("/api/knowledge/upload")
async def upload_knowledge_file(
    request:     Request,
    file:        UploadFile = File(...),
    description: Optional[str] = Form(None),
):
    """上传文件并分块写入长期记忆（ChromaDB）。"""
    user_id  = getattr(request.state, "user_id", "default")
    filename = file.filename or "unknown"
    ext      = os.path.splitext(filename)[1].lower()

    if ext not in _ALLOWED_EXT:
        return JSONResponse(
            status_code=400,
            content={"success": False, "message": f"不支持 {ext}，可用: {', '.join(sorted(_ALLOWED_EXT))}"},
        )

    # 先检查文件大小（无需全量读取内容）
    file.file.seek(0, 2)
    file_size = file.file.tell()
    file.file.seek(0)
    if file_size > _MAX_BYTES:
        return JSONResponse(
            status_code=400,
            content={"success": False, "message": "文件超过 10 MB 上限"},
        )

    content = await file.read()

    try:
        text = _extract_text(filename, content)
    except ValueError as e:
        return JSONResponse(status_code=400, content={"success": False, "message": str(e)})

    if not text.strip():
        return JSONResponse(status_code=400, content={"success": False, "message": "文件内容为空，无法入库"})

    chunks  = _chunk_text(text)
    file_id = str(uuid.uuid4())
    now     = datetime.now().isoformat()

    chunk_ids: List[str] = []
    if _state.agent:
        ltm = _state.agent.memory_manager.long_term
        items = [
            {
                "content": chunk,
                "metadata": {
                    "source":      "file",
                    "filename":    filename,
                    "file_id":     file_id,
                    "chunk_index": i,
                    "category":    "knowledge",
                    "user_id":     user_id,
                    "description": description or "",
                },
                "importance": 0.7,
            }
            for i, chunk in enumerate(chunks)
        ]
        stored    = ltm.store_batch(items)
        chunk_ids = [m.id for m in stored]
    else:
        logger.warning("Agent 未初始化，文件分块未写入向量库")

    manifest             = _load_manifest(user_id)
    manifest[file_id]    = {
        "file_id":     file_id,
        "filename":    filename,
        "uploaded_at": now,
        "chunk_count": len(chunks),
        "chunk_ids":   chunk_ids,
        "description": description or "",
        "size_bytes":  len(content),
        "char_count":  len(text),
    }
    _save_manifest(user_id, manifest)

    logger.info(f"文件入库完成: user={user_id}, file={filename}, chunks={len(chunks)}, file_id={file_id}")
    return {
        "success":     True,
        "file_id":     file_id,
        "filename":    filename,
        "chunk_count": len(chunks),
        "char_count":  len(text),
    }


@router.get("/api/knowledge/files")
async def list_knowledge_files(request: Request):
    user_id = getattr(request.state, "user_id", "default")
    manifest = _load_manifest(user_id)
    files = sorted(manifest.values(), key=lambda x: x.get("uploaded_at", ""), reverse=True)
    return {"success": True, "files": files, "count": len(files)}


@router.delete("/api/knowledge/files/{file_id}")
async def delete_knowledge_file(file_id: str, request: Request):
    user_id  = getattr(request.state, "user_id", "default")
    manifest = _load_manifest(user_id)
    entry    = manifest.get(file_id)
    if not entry:
        return JSONResponse(status_code=404, content={"success": False, "message": "文件不存在"})

    deleted_chunks = 0
    if _state.agent:
        ltm = _state.agent.memory_manager.long_term
        for chunk_id in entry.get("chunk_ids", []):
            try:
                ltm.delete(chunk_id)
                deleted_chunks += 1
            except Exception as e:
                logger.warning(f"删除 chunk {chunk_id} 失败: {e}")

    del manifest[file_id]
    _save_manifest(user_id, manifest)

    logger.info(
        f"文件已删除: user={user_id}, file={entry['filename']}, "
        f"file_id={file_id}, deleted_chunks={deleted_chunks}"
    )
    return {"success": True, "file_id": file_id, "deleted_chunks": deleted_chunks}

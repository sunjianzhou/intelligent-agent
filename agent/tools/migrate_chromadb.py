#!/usr/bin/env python3
"""ChromaDB seq_id 迁移脚本

问题根因：ChromaDB <0.4.0 用 INTEGER 存储 seq_id，>=0.4.0 改为 BLOB。
混版数据会在 count()/query() 时抛出 TypeError，导致向量搜索静默失效。

修复方式：
  1. 通过 collection.get(include=['embeddings','documents','metadatas']) 读出
     所有文档（此接口走 SQLite 直读，不触发 HNSW seq_id 类型检查）
  2. 删除旧集合
  3. 重建集合并批量插入

用法（在 agent/ 目录下运行）：
  python tools/migrate_chromadb.py            # 检测 + 修复
  python tools/migrate_chromadb.py --dry-run  # 只检测，不修改数据
"""
import sys
import argparse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))


def _check_broken(col) -> bool:
    """返回 True 表示该集合有 seq_id 类型问题。"""
    try:
        col.count()
        return False
    except TypeError:
        return True


def _migrate_one(client, col_name: str, dry_run: bool) -> str:
    """迁移单个集合。返回状态字符串：ok / broken-fixed / broken-failed / skipped。"""
    try:
        col = client.get_collection(name=col_name)
    except Exception as e:
        return f"skipped (open failed: {e})"

    if not _check_broken(col):
        return "ok"

    if dry_run:
        return "broken (--dry-run, not fixed)"

    # 读取全量数据（不走 HNSW）
    try:
        all_data = col.get(include=["embeddings", "documents", "metadatas"])
    except Exception as e:
        return f"broken-failed (read error: {e})"

    ids        = all_data.get("ids") or []
    embeddings = all_data.get("embeddings") or []
    documents  = all_data.get("documents") or []
    metadatas  = all_data.get("metadatas") or []

    # 删除旧集合
    try:
        client.delete_collection(name=col_name)
    except Exception as e:
        return f"broken-failed (delete error: {e})"

    # 重建集合
    try:
        new_col = client.create_collection(
            name=col_name,
            metadata={"hnsw:space": "cosine"},
        )
    except Exception as e:
        return f"broken-failed (recreate error: {e})"

    # 批量插入（每批 100 条，避免单次太大）
    batch = 100
    inserted = 0
    for i in range(0, len(ids), batch):
        sl = slice(i, i + batch)
        kwargs: dict = {"ids": ids[sl]}
        if documents:  kwargs["documents"]  = documents[sl]
        if metadatas:  kwargs["metadatas"]  = metadatas[sl]
        if embeddings: kwargs["embeddings"] = embeddings[sl]
        try:
            new_col.add(**kwargs)
            inserted += len(ids[sl])
        except Exception as e:
            print(f"    ⚠ 批次 {i//batch} 插入失败: {e}")

    return f"broken-fixed ({inserted}/{len(ids)} docs)"


def _process_dir(label: str, path_str: str, dry_run: bool) -> None:
    path = Path(path_str)
    print(f"\n{'='*60}")
    print(f"[{label}] {path_str}")

    if not path.exists():
        print("  目录不存在，跳过")
        return

    try:
        import chromadb
        from chromadb.config import Settings as CSettings
        client = chromadb.PersistentClient(
            path=str(path),
            settings=CSettings(anonymized_telemetry=False),
        )
    except Exception as e:
        print(f"  无法连接 ChromaDB: {e}")
        return

    cols = client.list_collections()
    print(f"  集合数量: {len(cols)}")

    broken_count = 0
    for c in cols:
        status = _migrate_one(client, c.name, dry_run)
        icon = "✅" if status.startswith("ok") else ("🔧" if "fixed" in status else "⚠")
        print(f"  {icon} {c.name}: {status}")
        if "broken" in status:
            broken_count += 1

    if broken_count == 0:
        print("  → 所有集合均正常，无需迁移")
    elif dry_run:
        print(f"  → 发现 {broken_count} 个问题集合（--dry-run，未修复）")
    else:
        print(f"  → 已处理 {broken_count} 个集合")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="修复 ChromaDB seq_id INTEGER→BLOB schema 不匹配问题"
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="只检测不修复（安全模式）"
    )
    args = parser.parse_args()

    from config.settings import settings

    dirs = [
        ("长期记忆 (chroma-data)",         settings.chroma_persist_dir),
        ("长期记忆 (chroma-data-longterm)", settings.long_term_persist_dir),
    ]

    print("ChromaDB seq_id 迁移工具")
    print("模式:", "检测（--dry-run）" if args.dry_run else "检测 + 自动修复")

    for label, path_str in dirs:
        _process_dir(label, path_str, args.dry_run)

    print(f"\n{'='*60}")
    print("完成。" if not args.dry_run else "检测完成（数据未修改）。")


if __name__ == "__main__":
    main()

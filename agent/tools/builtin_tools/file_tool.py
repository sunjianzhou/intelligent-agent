"""文件操作工具"""
import os
import json
import csv
import shutil
from pathlib import Path
from typing import Dict, Any, List, Optional
from tools.base_tool import BaseTool, ToolResult

# soul/MEMORY.md 绝对路径，由文件位置锚定（不依赖 CWD），供 TODO-83 记忆归并使用
_PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent
MEMORY_MD_PATH = os.path.abspath(str(_PROJECT_ROOT / "soul" / "MEMORY.md"))


class FileTool(BaseTool):
    """文件操作工具

    注意：文件操作有风险，需要谨慎使用
    """

    def __init__(self):
        description = "文件和目录操作工具。" \
                      "支持的 action: read(读取全文), write(写入), list(列目录), create, delete, copy, move, info, exists。" \
                      "读取前N行请用 action=read 获取全文后自行截取。"
        super().__init__(description=description)
        self.requires_auth = True
        self.safe_directories = [str(Path.home()), os.getcwd()]
        # 额外授权的单文件白名单（TODO-83）：允许 soul/MEMORY.md 在 safe_directories
        # 之外仍可读写，但 _check_path_safety 会禁止对它执行 delete/move，
        # 防止心跳记忆归并的自治 LLM 调用误删/误移走唯一副本。
        self._extra_writable_files = [MEMORY_MD_PATH]

    def execute(self, action: str, path: str, **kwargs) -> Any:
        """执行文件操作

        Args:
            action: 操作类型
            path: 文件路径
            **kwargs: 其他参数

        Returns:
            操作结果
        """
        # 安全检查
        self._check_path_safety(path, action)

        action_handlers = {
            "read": self._read_file,
            "write": self._write_file,
            "list": self._list_directory,
            "create": self._create_file,
            "delete": self._delete_file,
            "copy": self._copy_file,
            "move": self._move_file,
            "info": self._get_file_info,
            "exists": self._check_exists,
        }

        if action not in action_handlers:
            raise ValueError(f"不支持的操作: {action}，可选: {list(action_handlers.keys())}")

        return action_handlers[action](path, **kwargs)

    def _check_path_safety(self, path: str, action: str = "") -> None:
        """检查路径安全性。

        白名单文件（如 soul/MEMORY.md）允许超出 safe_directories 范围读写，
        但禁止 delete/move，防止自治记忆归并把唯一副本删掉或移走。
        """
        abs_path = os.path.abspath(path)

        if abs_path in self._extra_writable_files:
            if action in ("delete", "move"):
                raise PermissionError(f"白名单文件禁止 {action} 操作: {path}")
            return

        # 检查是否在安全目录内
        is_safe = any(abs_path.startswith(safe_dir) for safe_dir in self.safe_directories)

        if not is_safe:
            raise PermissionError(f"路径不在安全目录内: {path}")

    def _read_file(self, path: str, encoding: str = "utf-8",
                  mode: str = "text", **kwargs) -> Dict[str, Any]:
        """读取文件

        Args:
            path: 文件路径
            encoding: 编码
            mode: 模式，text, binary, json, csv

        Returns:
            文件内容
        """
        if mode == "text":
            with open(path, 'r', encoding=encoding) as f:
                content = f.read()

            return {
                "path": path,
                "content": content,
                "size": len(content),
                "lines": len(content.splitlines()),
                "encoding": encoding,
            }

        elif mode == "binary":
            with open(path, 'rb') as f:
                content = f.read()

            return {
                "path": path,
                "size": len(content),
                "type": "binary",
            }

        elif mode == "json":
            with open(path, 'r', encoding=encoding) as f:
                data = json.load(f)

            return {
                "path": path,
                "data": data,
                "type": "json",
                "size": os.path.getsize(path),
            }

        elif mode == "csv":
            with open(path, 'r', encoding=encoding, newline='') as f:
                reader = csv.reader(f)
                rows = list(reader)

            return {
                "path": path,
                "rows": rows,
                "row_count": len(rows),
                "type": "csv",
            }

        else:
            raise ValueError(f"不支持的读取模式: {mode}")

    def _write_file(self, path: str, content: Any, encoding: str = "utf-8",
                   mode: str = "text", **kwargs) -> Dict[str, Any]:
        """写入文件

        Args:
            path: 文件路径
            content: 内容
            encoding: 编码
            mode: 模式，text, json, append

        Returns:
            写入结果
        """
        # 确保目录存在
        os.makedirs(os.path.dirname(path), exist_ok=True)

        if mode == "text":
            with open(path, 'w', encoding=encoding) as f:
                f.write(str(content))

        elif mode == "json":
            with open(path, 'w', encoding=encoding) as f:
                json.dump(content, f, indent=2, ensure_ascii=False)

        elif mode == "append":
            with open(path, 'a', encoding=encoding) as f:
                f.write(str(content))

        else:
            raise ValueError(f"不支持的写入模式: {mode}")

        return {
            "path": path,
            "action": "write",
            "mode": mode,
            "size": os.path.getsize(path) if os.path.exists(path) else 0,
            "message": f"文件已写入: {path}"
        }

    def _list_directory(self, path: str, recursive: bool = False,
                       filter_pattern: str = None, **kwargs) -> Dict[str, Any]:
        """列出目录内容"""
        if not os.path.exists(path):
            raise FileNotFoundError(f"目录不存在: {path}")

        if not os.path.isdir(path):
            raise NotADirectoryError(f"不是目录: {path}")

        items = []

        if recursive:
            for root, dirs, files in os.walk(path):
                for name in dirs + files:
                    full_path = os.path.join(root, name)
                    rel_path = os.path.relpath(full_path, path)

                    items.append({
                        "name": name,
                        "path": rel_path,
                        "full_path": full_path,
                        "is_dir": os.path.isdir(full_path),
                        "size": os.path.getsize(full_path) if os.path.isfile(full_path) else 0,
                    })
        else:
            for item in os.listdir(path):
                full_path = os.path.join(path, item)

                items.append({
                    "name": item,
                    "path": item,
                    "full_path": full_path,
                    "is_dir": os.path.isdir(full_path),
                    "size": os.path.getsize(full_path) if os.path.isfile(full_path) else 0,
                })

        # 过滤
        if filter_pattern:
            import fnmatch
            items = [item for item in items if fnmatch.fnmatch(item["name"], filter_pattern)]

        return {
            "path": path,
            "item_count": len(items),
            "items": items,
            "recursive": recursive,
        }

    def _create_file(self, path: str, content: str = "", **kwargs) -> Dict[str, Any]:
        """创建文件"""
        if os.path.exists(path):
            raise FileExistsError(f"文件已存在: {path}")

        os.makedirs(os.path.dirname(path), exist_ok=True)

        with open(path, 'w', encoding='utf-8') as f:
            f.write(content)

        return {
            "path": path,
            "action": "create",
            "size": len(content),
            "message": f"文件已创建: {path}"
        }

    def _delete_file(self, path: str, **kwargs) -> Dict[str, Any]:
        """删除文件或目录"""
        if not os.path.exists(path):
            raise FileNotFoundError(f"路径不存在: {path}")

        if os.path.isdir(path):
            shutil.rmtree(path)
            action = "delete_directory"
        else:
            os.remove(path)
            action = "delete_file"

        return {
            "path": path,
            "action": action,
            "message": f"已删除: {path}"
        }

    def _copy_file(self, src: str, dst: str, **kwargs) -> Dict[str, Any]:
        """复制文件"""
        if not os.path.exists(src):
            raise FileNotFoundError(f"源文件不存在: {src}")

        os.makedirs(os.path.dirname(dst), exist_ok=True)

        if os.path.isdir(src):
            shutil.copytree(src, dst)
            action = "copy_directory"
        else:
            shutil.copy2(src, dst)
            action = "copy_file"

        return {
            "source": src,
            "destination": dst,
            "action": action,
            "message": f"已复制: {src} -> {dst}"
        }

    def _move_file(self, src: str, dst: str, **kwargs) -> Dict[str, Any]:
        """移动/重命名文件"""
        if not os.path.exists(src):
            raise FileNotFoundError(f"源文件不存在: {src}")

        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.move(src, dst)

        return {
            "source": src,
            "destination": dst,
            "action": "move",
            "message": f"已移动: {src} -> {dst}"
        }

    def _get_file_info(self, path: str, **kwargs) -> Dict[str, Any]:
        """获取文件信息"""
        if not os.path.exists(path):
            raise FileNotFoundError(f"文件不存在: {path}")

        stat = os.stat(path)

        return {
            "path": path,
            "exists": True,
            "is_file": os.path.isfile(path),
            "is_dir": os.path.isdir(path),
            "size": stat.st_size,
            "created": stat.st_ctime,
            "modified": stat.st_mtime,
            "accessed": stat.st_atime,
            "permissions": oct(stat.st_mode)[-3:],
        }

    def _check_exists(self, path: str, **kwargs) -> Dict[str, Any]:
        """检查文件是否存在"""
        return {
            "path": path,
            "exists": os.path.exists(path),
            "is_file": os.path.isfile(path) if os.path.exists(path) else False,
            "is_dir": os.path.isdir(path) if os.path.exists(path) else False,
        }
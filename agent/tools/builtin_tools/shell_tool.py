"""Shell 命令执行工具（白名单安全模式）"""
import re
import subprocess
from typing import Any
from tools.base_tool import BaseTool

# 允许执行的命令前缀白名单（只读/信息类）
_ALLOWED_PREFIXES = (
    # 基础信息
    "echo", "date", "time", "whoami", "hostname", "uname",
    # 文件/目录查看
    "ls", "dir", "pwd", "cat", "head", "tail", "wc", "find", "grep",
    # 网络
    "ping", "curl", "wget", "nslookup", "netstat", "ss",
    # 进程/系统信息
    "ps", "top", "df", "du", "free", "uptime", "env", "printenv",
    # 语言运行时
    "python", "python3", "node", "java -version",
    # Git 只读操作
    "git log", "git status", "git diff", "git branch", "git show", "git tag",
)

# 拒绝包含敏感文件路径的命令（防止泄露密钥、密码等）
_SENSITIVE_PATTERNS = re.compile(
    r"(\.env|id_rsa|id_ecdsa|\.pem|\.key|\.p12|shadow|passwd|credentials|secret)",
    re.IGNORECASE,
)


def _is_allowed(cmd: str) -> bool:
    c = cmd.strip().lower()
    return any(c.startswith(p) for p in _ALLOWED_PREFIXES)


def _has_sensitive_path(cmd: str) -> bool:
    """命令中含有敏感文件名关键词时拒绝执行。"""
    return bool(_SENSITIVE_PATTERNS.search(cmd))


class ShellTool(BaseTool):
    """安全 Shell 命令执行工具（白名单模式）。
    支持只读/信息类命令，禁止 rm/del/format 等破坏性操作。
    参数: command(要执行的命令,必填), timeout(超时秒数,默认10)
    """

    def __init__(self):
        super().__init__(
            description=(
                "执行安全的 Shell 命令并返回输出。"
                "参数: command(命令字符串,必填), timeout(超时秒数,默认10,最大30)"
            )
        )

    def execute(self, command: str, timeout: int = 10) -> dict[str, Any]:
        if not command or not command.strip():
            return {"success": False, "error": "command 不能为空"}

        if not _is_allowed(command):
            return {
                "success": False,
                "error": f"命令 '{command.split()[0]}' 不在允许列表中，仅支持只读/信息类命令",
            }

        if _has_sensitive_path(command):
            return {
                "success": False,
                "error": "命令包含敏感文件路径（.env / 密钥文件等），已拒绝执行",
            }

        timeout = min(int(timeout), 30)
        try:
            result = subprocess.run(
                command,
                shell=True,
                capture_output=True,
                text=True,
                timeout=timeout,
                encoding="utf-8",
                errors="replace",
            )
            output = result.stdout or result.stderr or "(无输出)"
            if len(output) > 2000:
                output = output[:2000] + "\n...(输出已截断)"
            return {
                "success": result.returncode == 0,
                "returncode": result.returncode,
                "output": output,
            }
        except subprocess.TimeoutExpired:
            return {"success": False, "error": f"命令超时（>{timeout}s）"}
        except Exception as e:
            return {"success": False, "error": str(e)}

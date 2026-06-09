"""MySQL 连接器实现"""
import re
from typing import List, Optional, Tuple
from loguru import logger

from .base_connector import BaseConnector, QueryResult


# 只读 SQL 白名单（关键词级别）
_READONLY_KEYWORDS = {"select", "show", "describe", "desc", "explain"}

# 危险关键词黑名单
_DANGEROUS_KEYWORDS = {
    "insert", "update", "delete", "drop", "truncate",
    "alter", "create", "replace", "call", "exec",
    "grant", "revoke", "load", "into outfile",
}


def _is_readonly_sql(sql: str) -> Tuple[bool, str]:
    """
    校验 SQL 是否为只读语句
    返回 (is_safe, reason)
    """
    cleaned = sql.strip().lower()
    # 去掉注释
    cleaned = re.sub(r'--[^\n]*', '', cleaned)
    cleaned = re.sub(r'/\*.*?\*/', '', cleaned, flags=re.DOTALL)
    cleaned = cleaned.strip()

    # 检查危险关键词
    for kw in _DANGEROUS_KEYWORDS:
        pattern = rf'\b{re.escape(kw)}\b'
        if re.search(pattern, cleaned):
            return False, f"不允许执行包含 '{kw}' 的语句（只读模式）"

    # 首个关键词必须在白名单内
    first_word = cleaned.split()[0] if cleaned.split() else ""
    if first_word not in _READONLY_KEYWORDS:
        return False, f"不支持 '{first_word}' 语句（只允许: {', '.join(sorted(_READONLY_KEYWORDS))}）"

    # 防止多语句（分号分隔）
    statements = [s.strip() for s in cleaned.split(";") if s.strip()]
    if len(statements) > 1:
        return False, "不允许多语句执行（请拆分成单条 SQL）"

    return True, ""


class MySQLConnector(BaseConnector):

    def __init__(self, host: str, port: int, database: str,
                 user: str, password: str, charset: str = "utf8mb4"):
        self.host     = host
        self.port     = port
        self.database = database
        self.user     = user
        self.password = password
        self.charset  = charset
        self._conn    = None

    @property
    def db_type(self) -> str:
        return "MySQL"

    def connect(self) -> bool:
        try:
            import pymysql
            self._conn = pymysql.connect(
                host     = self.host,
                port     = self.port,
                database = self.database,
                user     = self.user,
                password = self.password,
                charset  = self.charset,
                connect_timeout = 10,
                cursorclass = pymysql.cursors.Cursor,
            )
            logger.info(f"MySQL 连接成功: {self.host}:{self.port}/{self.database}")
            return True
        except Exception as e:
            logger.error(f"MySQL 连接失败: {e}")
            return False

    def disconnect(self):
        if self._conn:
            try:
                self._conn.close()
            except Exception:
                pass
            self._conn = None

    def _ensure_connected(self):
        """确保连接有效，断线自动重连"""
        try:
            if self._conn:
                self._conn.ping(reconnect=True)
                return
        except Exception:
            pass
        self.connect()

    def execute_query(self, sql: str,
                      params: Optional[tuple] = None) -> QueryResult:
        # 安全校验
        is_safe, reason = _is_readonly_sql(sql)
        if not is_safe:
            return QueryResult([], [], 0, error=reason)

        self._ensure_connected()
        if not self._conn:
            return QueryResult([], [], 0, error="数据库连接不可用")

        try:
            with self._conn.cursor() as cursor:
                cursor.execute(sql, params)
                columns = [desc[0] for desc in cursor.description] if cursor.description else []
                rows    = [list(row) for row in cursor.fetchall()]
                # 处理不可序列化的类型
                for row in rows:
                    for i, val in enumerate(row):
                        if hasattr(val, 'isoformat'):   # datetime/date
                            row[i] = val.isoformat()
                        elif isinstance(val, bytes):
                            row[i] = val.decode('utf-8', errors='replace')
                return QueryResult(columns, rows, len(rows))
        except Exception as e:
            logger.warning(f"SQL 执行失败: {e}\nSQL: {sql}")
            return QueryResult([], [], 0, error=str(e))

    def get_tables(self) -> List[str]:
        result = self.execute_query("SHOW TABLES")
        if result.error:
            return []
        return [row[0] for row in result.rows]

    def get_table_schema(self, table_name: str) -> QueryResult:
        # 表名只允许字母数字下划线
        if not re.match(r'^[a-zA-Z0-9_]+$', table_name):
            return QueryResult([], [], 0, error=f"非法表名: {table_name}")
        return self.execute_query(f"DESCRIBE `{table_name}`")

    def test_connection(self) -> bool:
        result = self.execute_query("SELECT 1")
        return not result.error
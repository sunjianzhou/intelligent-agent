"""数据库工具 —— Agent 工具入口"""
from typing import Any, Dict, Optional
from loguru import logger

from tools.base_tool import BaseTool
from config.settings import settings
from .mysql_connector import MySQLConnector
from .base_connector import BaseConnector


def _build_connector() -> Optional[BaseConnector]:
    """根据配置构建对应的连接器（后续加 OceanBase 在这里扩展）"""
    db_type = (settings.db_type or "").lower()

    if db_type == "mysql":
        return MySQLConnector(
            host     = settings.db_host,
            port     = settings.db_port,
            database = settings.db_database,
            user     = settings.db_user,
            password = settings.db_password,
            charset  = settings.db_charset,
        )
    # elif db_type == "oceanbase":
    #     return OceanBaseConnector(...)

    logger.warning(f"不支持的数据库类型: {db_type}")
    return None


class DatabaseTool(BaseTool):
    """
    数据库查询工具（只读）

    支持的 action：
      - query      执行 SELECT/SHOW/DESCRIBE SQL
      - list_tables  列出所有表
      - describe   查看表结构
      - sample     查看表前 N 行样本数据
    """

    name        = "DatabaseTool"
    description = (
        "数据库只读查询工具。"
        "action=query 执行SQL查询；"
        "action=list_tables 列出所有表；"
        "action=describe 查看表结构，需要 table 参数；"
        "action=sample 查看表样本数据，需要 table 参数，可选 limit 参数（默认10）。"
        "只支持只读操作，禁止 INSERT/UPDATE/DELETE/DROP 等写操作。"
    )

    def __init__(self):
        super().__init__()
        self._connector: Optional[BaseConnector] = None
        self._init_connector()

    def _init_connector(self):
        if not settings.db_database:
            logger.info("未配置数据库，DatabaseTool 不可用")
            return
        self._connector = _build_connector()
        if self._connector:
            ok = self._connector.connect()
            if not ok:
                logger.warning("DatabaseTool: 数据库初始连接失败，后续使用时会自动重试")

    def execute(self, action: str = "query",
                sql: str = "",
                table: str = "",
                limit: int = 10,
                **kwargs) -> Dict[str, Any]:

        if not self._connector:
            return self._err("数据库工具未初始化，请检查 .env 中的 DB_* 配置")

        action = action.lower().strip()

        if action == "list_tables":
            return self._list_tables()

        if action == "describe":
            if not table:
                return self._err("describe 操作需要提供 table 参数")
            return self._describe(table)

        if action == "sample":
            if not table:
                return self._err("sample 操作需要提供 table 参数")
            return self._sample(table, min(int(limit), 100))

        if action == "query":
            if not sql:
                return self._err("query 操作需要提供 sql 参数")
            return self._query(sql)

        return self._err(
            f"不支持的 action: '{action}'，"
            "可选值: query / list_tables / describe / sample"
        )

    # ── 具体操作 ──────────────────────────────────────────

    def _list_tables(self) -> Dict[str, Any]:
        tables = self._connector.get_tables()
        if not tables:
            return {"success": True, "result": "当前库中没有表或获取失败",
                    "tables": [], "count": 0}
        return {
            "success": True,
            "result":  f"数据库 [{self._connector.database}] 共有 {len(tables)} 张表：\n"
                       + "\n".join(f"  - {t}" for t in tables),
            "tables":  tables,
            "count":   len(tables),
        }

    def _describe(self, table: str) -> Dict[str, Any]:
        result = self._connector.get_table_schema(table)
        if result.error:
            return self._err(result.error)
        return {
            "success":   True,
            "result":    f"表 [{table}] 结构：\n" + result.to_readable(),
            "columns":   result.columns,
            "rows":      result.rows,
            "row_count": result.row_count,
        }

    def _sample(self, table: str, limit: int) -> Dict[str, Any]:
        import re
        if not re.match(r'^[a-zA-Z0-9_]+$', table):
            return self._err(f"非法表名: {table}")
        result = self._connector.execute_query(
            f"SELECT * FROM `{table}` LIMIT %s", (limit,)
        )
        if result.error:
            return self._err(result.error)
        return {
            "success":   True,
            "result":    f"表 [{table}] 前 {limit} 行数据：\n" + result.to_readable(),
            "columns":   result.columns,
            "rows":      result.rows,
            "row_count": result.row_count,
        }

    def _query(self, sql: str) -> Dict[str, Any]:
        result = self._connector.execute_query(sql)
        if result.error:
            return self._err(result.error)
        return {
            "success":   True,
            "result":    result.to_readable(),
            "columns":   result.columns,
            "rows":      result.rows,
            "row_count": result.row_count,
        }

    @staticmethod
    def _err(msg: str) -> Dict[str, Any]:
        return {"success": False, "result": f"DatabaseTool 错误: {msg}", "error": msg}

    def get_schema(self) -> Dict[str, Any]:
        return {
            "name":        self.name,
            "description": self.description,
            "parameters": {
                "type":     "object",
                "properties": {
                    "action": {
                        "type":        "string",
                        "enum":        ["query", "list_tables", "describe", "sample"],
                        "description": "操作类型"
                    },
                    "sql": {
                        "type":        "string",
                        "description": "action=query 时必填，SELECT/SHOW/DESCRIBE SQL 语句"
                    },
                    "table": {
                        "type":        "string",
                        "description": "action=describe 或 sample 时必填，表名"
                    },
                    "limit": {
                        "type":        "integer",
                        "description": "action=sample 时可选，返回行数，默认10，最大100",
                        "default":     10
                    },
                },
                "required": ["action"],
            }
        }
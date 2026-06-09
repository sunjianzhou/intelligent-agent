"""数据库连接器抽象基类，后续扩展 OceanBase 只需继承此类"""
from abc import ABC, abstractmethod
from typing import List, Dict, Any, Optional


class QueryResult:
    """统一查询结果格式"""
    def __init__(self, columns: List[str], rows: List[List[Any]],
                 row_count: int, error: str = ""):
        self.columns   = columns
        self.rows      = rows
        self.row_count = row_count
        self.error     = error

    def to_dict(self) -> Dict[str, Any]:
        return {
            "columns":   self.columns,
            "rows":      self.rows,
            "row_count": self.row_count,
            "error":     self.error,
        }

    def to_readable(self, max_rows: int = 50) -> str:
        """转换为易读的文本格式"""
        if self.error:
            return f"查询失败: {self.error}"
        if not self.rows:
            return "查询成功，无数据返回"

        display_rows = self.rows[:max_rows]
        truncated    = len(self.rows) > max_rows

        # 计算每列宽度
        col_widths = [len(str(c)) for c in self.columns]
        for row in display_rows:
            for i, val in enumerate(row):
                col_widths[i] = max(col_widths[i], len(str(val) if val is not None else "NULL"))

        sep  = "+" + "+".join("-" * (w + 2) for w in col_widths) + "+"
        head = "|" + "|".join(f" {c:<{col_widths[i]}} " for i, c in enumerate(self.columns)) + "|"

        lines = [sep, head, sep]
        for row in display_rows:
            cells = []
            for i, val in enumerate(row):
                s = str(val) if val is not None else "NULL"
                cells.append(f" {s:<{col_widths[i]}} ")
            lines.append("|" + "|".join(cells) + "|")
        lines.append(sep)
        lines.append(f"共 {self.row_count} 条记录" + (f"，仅显示前 {max_rows} 条" if truncated else ""))

        return "\n".join(lines)


class BaseConnector(ABC):
    """数据库连接器抽象基类"""

    @abstractmethod
    def connect(self) -> bool:
        """建立连接，返回是否成功"""

    @abstractmethod
    def disconnect(self):
        """断开连接"""

    @abstractmethod
    def execute_query(self, sql: str,
                      params: Optional[tuple] = None) -> QueryResult:
        """执行只读查询"""

    @abstractmethod
    def get_tables(self) -> List[str]:
        """获取当前库所有表名"""

    @abstractmethod
    def get_table_schema(self, table_name: str) -> QueryResult:
        """获取表结构"""

    @abstractmethod
    def test_connection(self) -> bool:
        """测试连接是否有效"""

    @property
    @abstractmethod
    def db_type(self) -> str:
        """数据库类型标识"""
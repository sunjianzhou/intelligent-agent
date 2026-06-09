"""网络搜索工具（DuckDuckGo，免费无需 API Key）"""
from typing import List, Dict, Any
from tools.base_tool import BaseTool


class WebSearchTool(BaseTool):
    """网络搜索工具，使用 DuckDuckGo 搜索，免费无需 API Key"""

    def __init__(self):
        super().__init__(
            description="使用 DuckDuckGo 搜索网络信息。参数: query(搜索关键词,必填), max_results(结果数量,默认5)"
        )

    def execute(self, query: str, max_results: int = 5) -> List[Dict[str, Any]]:
        """执行网络搜索

        Args:
            query: 搜索关键词
            max_results: 返回结果数量，默认 5

        Returns:
            搜索结果列表
        """
        try:
            from duckduckgo_search import DDGS
            results = []
            with DDGS(timeout=15) as ddgs:
                for r in ddgs.text(query, max_results=max(1, min(max_results, 10))):
                    results.append({
                        "title":   r.get("title", ""),
                        "url":     r.get("href", ""),
                        "snippet": r.get("body", ""),
                    })
            if not results:
                return [{"title": "无结果", "url": "", "snippet": f"未找到「{query}」的相关内容"}]
            return results
        except Exception as e:
            err_msg = str(e)
            if "timeout" in err_msg.lower() or "timed out" in err_msg.lower():
                err_msg = f"搜索超时，请检查网络连接或稍后重试（query: {query}）"
            return [{"title": "搜索失败", "url": "", "snippet": err_msg}]
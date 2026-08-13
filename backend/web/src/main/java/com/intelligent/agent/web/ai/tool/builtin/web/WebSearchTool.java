package com.intelligent.agent.web.ai.tool.builtin.web;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络搜索工具（TODO-110 Task 1）：DuckDuckGo HTML 端点，免费无需 API Key。
 * baseUrl 形如 "https://html.duckduckgo.com/html/?q="（可注入，便于测试）。
 */
public class WebSearchTool implements AgentTool {

    private final String searchUrlPrefix;
    private final int timeoutSeconds;

    public WebSearchTool() {
        this("https://html.duckduckgo.com/html/?q=", 15);
    }

    public WebSearchTool(String searchUrlPrefix, int timeoutSeconds) {
        this.searchUrlPrefix = searchUrlPrefix;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "web_search", "使用 DuckDuckGo 搜索网络信息。参数: query(搜索关键词,必填),"
                        + " max_results(结果数量,默认5,最大10)", true, null, Duration.ofSeconds(20),
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索关键词"),
                                "max_results", Map.of("type", "integer",
                                        "description", "结果数量，默认 5，最大 10")),
                        "required", List.of("query")));
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        String query = String.valueOf(arguments.getOrDefault("query", "")).trim();
        int maxResults = Math.max(1, Math.min(
                arguments.get("max_results") instanceof Number
                        ? ((Number) arguments.get("max_results")).intValue() : 5, 10));
        if (query.isEmpty()) {
            return List.of(Map.of("title", "搜索失败", "url", "", "snippet", "query 不能为空"));
        }
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            Document doc = Jsoup.connect(searchUrlPrefix + encoded)
                    .timeout(timeoutSeconds * 1000)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .get();
            Elements titles = doc.select(".result__a");
            Elements snippets = doc.select(".result__snippet");
            List<Map<String, Object>> results = new ArrayList<>();
            for (int i = 0; i < titles.size() && results.size() < maxResults; i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("title", titles.get(i).text());
                item.put("url", titles.get(i).absUrl("href"));
                item.put("snippet", i < snippets.size() ? snippets.get(i).text() : "");
                results.add(item);
            }
            if (results.isEmpty()) {
                return List.of(Map.of("title", "无结果", "url", "", "snippet", "未找到「" + query + "」的相关内容"));
            }
            return results;
        } catch (Exception e) {
            String message = e.getMessage() == null ? "未知错误" : e.getMessage();
            if (message.toLowerCase().contains("timeout")) {
                message = "搜索超时，请检查网络连接或稍后重试（query: " + query + "）";
            }
            return List.of(Map.of("title", "搜索失败", "url", "", "snippet", message));
        }
    }
}

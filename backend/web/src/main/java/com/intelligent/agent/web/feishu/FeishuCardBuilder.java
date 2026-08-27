package com.intelligent.agent.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

public final class FeishuCardBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FeishuCardBuilder() {}

    public static String textCard(String title, String content) {
        try {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("config", singletonMap("wide_screen_mode", true));
            card.put("header", header(title, "blue"));
            List<Object> elements = new ArrayList<>();
            elements.add(markdownDiv(content));
            card.put("elements", elements);
            return MAPPER.writeValueAsString(card);
        } catch (Exception e) {
            throw new RuntimeException("构建 textCard 失败", e);
        }
    }

    public static String tableCard(String title, List<Map<String, Object>> rows) {
        try {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("config", singletonMap("wide_screen_mode", true));
            card.put("header", header(title, "purple"));
            List<Object> elements = new ArrayList<>();
            if (!rows.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                Set<String> cols = rows.get(0).keySet();
                sb.append("| ").append(String.join(" | ", cols)).append(" |\n");
                sb.append("| ").append(String.join(" | ", Collections.nCopies(cols.size(), "---"))).append(" |\n");
                for (Map<String, Object> row : rows) {
                    List<String> vals = new ArrayList<>();
                    for (String col : cols) {
                        vals.add(String.valueOf(row.getOrDefault(col, "")));
                    }
                    sb.append("| ").append(String.join(" | ", vals)).append(" |\n");
                }
                elements.add(markdownDiv(sb.toString()));
            }
            card.put("elements", elements);
            return MAPPER.writeValueAsString(card);
        } catch (Exception e) {
            throw new RuntimeException("构建 tableCard 失败", e);
        }
    }

    public static String buttonCard(String title, String content,
                                    List<Map<String, String>> buttons) {
        try {
            return MAPPER.writeValueAsString(buttonCardMap(title, content, buttons));
        } catch (Exception e) {
            throw new RuntimeException("构建 buttonCard 失败", e);
        }
    }

    /**
     * R-09：HITL 审批卡片（批准/拒绝按钮，value.key = approval:approve|reject:&lt;approvalId&gt;，
     * 与 FeishuEventController 卡片回调解析约定一致）。返回 Map 供 ChannelAdapter 直接发送。
     */
    public static Map<String, Object> approvalCard(String approvalId,
                                                   Map<String, Object> eventData) {
        String tool = String.valueOf(eventData.getOrDefault("tool", "未知工具"));
        Object args = eventData.get("args");
        String content = "⚠️ **待审批操作**\n\n工具：`" + tool + "`\n\n参数：```json\n"
                + (args == null ? "{}" : args) + "\n```\n\n批准后立即执行，请确认。";
        return buttonCardMap("操作审批", content, List.of(
                Map.of("text", "✅ 批准", "value", "approval:approve:" + approvalId),
                Map.of("text", "❌ 拒绝", "value", "approval:reject:" + approvalId)));
    }

    private static Map<String, Object> buttonCardMap(String title, String content,
                                                     List<Map<String, String>> buttons) {
        try {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("config", singletonMap("wide_screen_mode", true));
            card.put("header", header(title, "orange"));
            List<Object> elements = new ArrayList<>();
            elements.add(markdownDiv(content));

            List<Object> btnList = new ArrayList<>();
            for (Map<String, String> btn : buttons) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("tag", "button");
                b.put("text", singletonMap2("tag", "plain_text", "content", btn.get("text")));
                b.put("type", "default");
                Map<String, Object> confirm = new LinkedHashMap<>();
                confirm.put("type", "plain_text");
                confirm.put("action_type", "callback");
                confirm.put("value", singletonMap("key", btn.get("value")));
                b.put("action", confirm);
                btnList.add(b);
            }
            Map<String, Object> actionRow = new LinkedHashMap<>();
            actionRow.put("tag", "action");
            actionRow.put("actions", btnList);
            elements.add(actionRow);

            card.put("elements", elements);
            return card;
        } catch (Exception e) {
            throw new RuntimeException("构建按钮卡片失败", e);
        }
    }

    private static Map<String, Object> header(String title, String template) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("title", singletonMap2("tag", "plain_text", "content", title));
        h.put("template", template);
        return h;
    }

    private static Map<String, Object> markdownDiv(String text) {
        Map<String, Object> div = new LinkedHashMap<>();
        div.put("tag", "div");
        div.put("text", singletonMap2("tag", "lark_md", "content", text));
        return div;
    }

    private static Map<String, Object> singletonMap(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static Map<String, Object> singletonMap2(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}

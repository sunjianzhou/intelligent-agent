package com.intelligent.agent.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class FeishuCardBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void textCard_hasHeaderAndContent() throws Exception {
        String json = FeishuCardBuilder.textCard("标题", "正文内容");
        Map<String, Object> card = mapper.readValue(json, Map.class);
        assertThat(card).containsKey("header");
        assertThat(card).containsKey("elements");
        Map<String, Object> header = (Map<String, Object>) card.get("header");
        Map<String, Object> title  = (Map<String, Object>) header.get("title");
        assertThat(title.get("content")).isEqualTo("标题");
    }

    @Test
    void textCard_specialChars_noJsonBreak() throws Exception {
        String json = FeishuCardBuilder.textCard("t", "line1\nline2 \"quoted\"");
        assertThatCode(() -> mapper.readValue(json, Map.class)).doesNotThrowAnyException();
    }

    @Test
    void tableCard_hasTable() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "Alice");
        row.put("score", 98);
        rows.add(row);
        String json = FeishuCardBuilder.tableCard("成绩单", rows);
        Map<String, Object> card = mapper.readValue(json, Map.class);
        assertThat(card).containsKey("elements");
    }

    @Test
    void buttonCard_hasActions() throws Exception {
        List<Map<String, String>> buttons = new ArrayList<>();
        Map<String, String> btn = new LinkedHashMap<>();
        btn.put("text", "确认");
        btn.put("value", "confirm");
        buttons.add(btn);
        String json = FeishuCardBuilder.buttonCard("确认操作", "请选择：", buttons);
        Map<String, Object> card = mapper.readValue(json, Map.class);
        List<Object> elements = (List<Object>) card.get("elements");
        boolean hasActions = elements.stream()
                .anyMatch(e -> "action".equals(((Map<String, Object>) e).get("tag")));
        assertThat(hasActions).isTrue();
    }
}

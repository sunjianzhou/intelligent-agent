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

    @Test
    void approvalCard_hasApproveAndRejectButtonsWithKeyConvention() throws Exception {
        Map<String, Object> card = FeishuCardBuilder.approvalCard("aprv_123",
                Map.of("tool", "channel_message", "args", Map.of("message", "hi")));

        assertThat(card).containsKey("header");
        List<Object> elements = (List<Object>) card.get("elements");
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Object e : elements) {
            if (e instanceof Map<?, ?> m && "action".equals(m.get("tag"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) m.get("actions");
                actions.addAll(list);
            }
        }
        assertThat(actions).hasSize(2);
        List<String> keys = new ArrayList<>();
        for (Map<String, Object> button : actions) {
            Map<?, ?> action = (Map<?, ?>) button.get("action");
            Map<?, ?> value = (Map<?, ?>) action.get("value");
            keys.add(String.valueOf(value.get("key")));
        }
        assertThat(keys).containsExactly(
                "approval:approve:aprv_123", "approval:reject:aprv_123");
    }
}

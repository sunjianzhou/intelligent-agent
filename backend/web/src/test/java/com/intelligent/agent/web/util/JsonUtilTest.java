package com.intelligent.agent.web.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class JsonUtilTest {

    @Test
    void toJson_simpleMap_producesValidJson() {
        Map<String, Object> map = new HashMap<>();
        map.put("key", "value");
        map.put("num", 42);
        String json = JsonUtil.toJson(map);
        assertThat(json).isNotNull();
        assertThat(json).contains("\"key\"");
        assertThat(json).contains("\"value\"");
        assertThat(json).contains("42");
    }

    @Test
    void fromJson_validJson_deserializesCorrectly() {
        String json = "{\"name\":\"alice\",\"age\":30}";
        @SuppressWarnings("unchecked")
        Map<String, Object> result = JsonUtil.fromJson(json, Map.class);
        assertThat(result).isNotNull();
        assertThat(result.get("name")).isEqualTo("alice");
        assertThat(result.get("age")).isEqualTo(30);
    }

    @Test
    void fromJson_invalidJson_returnsNull() {
        String result = JsonUtil.fromJson("not-json", String.class);
        assertThat(result).isNull();
    }

    @Test
    void toJson_nestedObject_roundtrips() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("x", 1);
        Map<String, Object> outer = new HashMap<>();
        outer.put("inner", inner);
        String json = JsonUtil.toJson(outer);
        assertThat(json).contains("\"inner\"");
        assertThat(json).contains("\"x\"");
    }

    static class Simple {
        public String name;
        public int score;
    }

    @Test
    void fromJson_toTypedClass_works() {
        String json = "{\"name\":\"bob\",\"score\":99}";
        Simple s = JsonUtil.fromJson(json, Simple.class);
        assertThat(s).isNotNull();
        assertThat(s.name).isEqualTo("bob");
        assertThat(s.score).isEqualTo(99);
    }
}

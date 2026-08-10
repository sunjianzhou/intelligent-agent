package com.intelligent.agent.web.ai.tool.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * heart_record 工具测试（TODO-110 Task 3.4）：
 * 心证 append/list/delete + 铁律 rule_add/list/delete/validate/rollback + 轮转备份。
 */
class HeartRecordToolTest {

    @TempDir
    Path tempDir;

    private HeartRecordTool tool;

    @BeforeEach
    void setUp() throws IOException {
        tool = new HeartRecordTool(tempDir);
        Files.writeString(tempDir.resolve("heart.md"), defaultHeart(), StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("rules.md"), defaultRules(), StandardCharsets.UTF_8);
    }

    // ── 心证 append / list / delete ──────────────────────────────

    @Test
    void appendsHeartEntryToSection() {
        Map<String, Object> result = (Map<String, Object>) tool.execute(Map.of(
                "action", "append", "content", "记住用户喜欢轻音乐", "category", "主人心证"));

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("section")).isEqualTo("主人心证");
        String text = readText(tempDir.resolve("heart.md"));
        assertThat(text).contains("- [" + java.time.LocalDate.now() + "] 记住用户喜欢轻音乐");
        // 轮转备份已创建
        assertThat(Files.exists(tempDir.resolve("heart.md.bak.1"))).isTrue();
    }

    @Test
    void appendRejectsEmptyContentAndBadCategory() {
        Map<String, Object> empty = (Map<String, Object>) tool.execute(Map.of(
                "action", "append", "content", "", "category", "主人心证"));
        assertThat(empty).containsKey("error");

        Map<String, Object> bad = (Map<String, Object>) tool.execute(Map.of(
                "action", "append", "content", "x", "category", "不存在"));
        assertThat(bad).containsKey("error");
    }

    @Test
    void listsHeartEntriesWithIds() {
        tool.execute(Map.of("action", "append", "content", "第一条", "category", "主人心证"));
        tool.execute(Map.of("action", "append", "content", "第二条", "category", "主人教诲"));

        Map<String, Object> all = (Map<String, Object>) tool.execute(Map.of("action", "list"));
        assertThat((int) all.get("total")).isEqualTo(2);

        Map<String, Object> filtered = (Map<String, Object>) tool.execute(Map.of(
                "action", "list", "category", "主人心证"));
        assertThat((int) filtered.get("total")).isEqualTo(1);
    }

    @Test
    void deletesHeartEntryById() {
        tool.execute(Map.of("action", "append", "content", "要删除的内容", "category", "主人心证"));
        Map<String, Object> list = (Map<String, Object>) tool.execute(Map.of("action", "list"));
        int id = (int) ((java.util.List<?>) list.get("entries")).stream()
                .findFirst()
                .map(e -> ((Map<?, ?>) e).get("id"))
                .orElseThrow();

        Map<String, Object> result = (Map<String, Object>) tool.execute(Map.of(
                "action", "delete", "id", String.valueOf(id)));
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(readText(tempDir.resolve("heart.md"))).doesNotContain("要删除的内容");
    }

    // ── 铁律 rule_add / rule_list / rule_delete ──────────────────

    @Test
    void ruleAddCreatesStructuredRule() {
        Map<String, Object> result = (Map<String, Object>) tool.execute(Map.of(
                "action", "rule_add",
                "rule_id", "RULE-101", "rule_title", "测试规则",
                "category", "安全边界", "rule_requirement", "禁止危险操作",
                "rule_priority", "critical", "rule_privacy", "public"));

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("version")).isEqualTo(1);
        String text = readText(tempDir.resolve("rules.md"));
        assertThat(text).contains("### RULE-101: 测试规则")
                .contains("- **重要度**: ★★★★★")
                .contains("- **隐私等级**: public");
    }

    @Test
    void ruleAddRejectsDuplicateActiveRule() {
        tool.execute(Map.of("action", "rule_add", "rule_id", "RULE-101",
                "rule_title", "测试规则", "category", "安全边界", "rule_requirement", "禁止危险操作"));

        Map<String, Object> dup = (Map<String, Object>) tool.execute(Map.of(
                "action", "rule_add", "rule_id", "RULE-101",
                "rule_title", "测试规则", "category", "安全边界", "rule_requirement", "禁止危险操作"));
        assertThat(dup).containsKey("error");
    }

    @Test
    void ruleAddBumpsVersionAndDeprecatesOld() {
        tool.execute(Map.of("action", "rule_add", "rule_id", "RULE-102",
                "rule_title", "版本规则", "category", "用户交互", "rule_requirement", "v1 诉求"));

        Map<String, Object> v2 = (Map<String, Object>) tool.execute(Map.of(
                "action", "rule_add", "rule_id", "RULE-102",
                "rule_title", "版本规则", "category", "用户交互", "rule_requirement", "v2 诉求"));
        assertThat(v2.get("version")).isEqualTo(2);

        String text = readText(tempDir.resolve("rules.md"));
        assertThat(text).contains("v2 诉求").contains("已废止（被 v2 取代");
    }

    @Test
    void ruleListReturnsActiveAndDeprecatedCounts() {
        tool.execute(Map.of("action", "rule_add", "rule_id", "RULE-103",
                "rule_title", "列表规则", "category", "模型绑定", "rule_requirement", "要求A"));

        Map<String, Object> list = (Map<String, Object>) tool.execute(Map.of("action", "rule_list"));
        assertThat(((Number) list.get("active")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat((int) list.get("total")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void ruleDeleteSoftDeprecates() {
        tool.execute(Map.of("action", "rule_add", "rule_id", "RULE-104",
                "rule_title", "删除测试", "category", "工具使用", "rule_requirement", "某要求"));

        Map<String, Object> result = (Map<String, Object>) tool.execute(Map.of(
                "action", "rule_delete", "rule_id", "RULE-104"));
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(readText(tempDir.resolve("rules.md"))).contains("已废止");
    }

    @Test
    void ruleValidateReportsMissing() {
        Map<String, Object> result = (Map<String, Object>) tool.execute(Map.of("action", "rule_validate"));
        assertThat(result).containsKey("missing");
        assertThat((int) result.get("total_in_file")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void ruleRollbackRestoresBackup() {
        String original = readText(tempDir.resolve("rules.md"));
        tool.execute(Map.of("action", "rule_add", "rule_id", "RULE-105",
                "rule_title", "回滚测试", "category", "隐私与数据", "rule_requirement", "不回滚则失败"));

        Map<String, Object> rollback = (Map<String, Object>) tool.execute(Map.of(
                "action", "rule_rollback", "bak_n", "1"));
        assertThat(rollback.get("ok")).isEqualTo(true);
        // 回滚到写入 RULE-105 之前的备份
        assertThat(readText(tempDir.resolve("rules.md"))).isEqualTo(original);
    }

    // ── helpers ──────────────────────────────────────────────────

    private static String defaultHeart() {
        return "# 心证铁卷\n\n## 主人心证\n<!-- 用户主动标记 -->\n\n## 主人教诲\n<!-- 指令 -->\n";
    }

    private static String defaultRules() {
        return "# 主人铁律\n\n## 安全边界\n\n## 模型绑定\n\n## 工具使用\n\n"
                + "## 失职与自查\n\n## 记忆与持久化\n\n## 用户交互\n\n## 隐私与数据\n";
    }

    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

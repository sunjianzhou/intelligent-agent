package com.intelligent.agent.web.ai.prompt;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SystemPromptBuilder 测试：段序、IM 渠道排除、persona 组装、空兜底。
 */
class SystemPromptBuilderTest {

    private final SystemPromptBuilder builder = new SystemPromptBuilder();

    private static SoulData soul() {
        return new SoulData(
                "soul核心", "user画像", "memory精选", "identity身份",
                "heartbeat自检", "whisper私密", "heart心证", "### RULE-001: 规则\n- **状态**: 现行\n- **隐私等级**: public\n- **具体诉求**: 遵守\n- **重要度**: ★★★",
                100, Map.of());
    }

    @Test
    void assemblesSectionsInFixedOrder() {
        String prompt = builder.build(soul(), null, "tool-overlay", "web", 8000);

        assertThat(prompt).contains("【灵魂核心】").contains("【身份】").contains("【用户画像】")
                .contains("【精选记忆】").contains("【心证铁卷】").contains("【主人铁律】")
                .contains("【自检铁规】").contains("【私密档案】").contains("tool-overlay");
        assertThat(prompt.indexOf("【灵魂核心】")).isLessThan(prompt.indexOf("【身份】"));
        assertThat(prompt.indexOf("【自检铁规】")).isLessThan(prompt.indexOf("tool-overlay"));
        // 段序：tool_overlay 始终最后
        assertThat(prompt.indexOf("tool-overlay")).isGreaterThan(prompt.indexOf("【私密档案】"));
    }

    @Test
    void imChannelExcludesHeartAndWhisper() {
        String prompt = builder.build(soul(), null, "", "feishu_im", 8000);

        assertThat(prompt).doesNotContain("【心证铁卷】").doesNotContain("【私密档案】");
        assertThat(prompt).contains("【灵魂核心】").contains("【主人铁律】");
    }

    @Test
    void webChannelIncludesHeartAndWhisper() {
        String prompt = builder.build(soul(), null, "", "web", 8000);

        assertThat(prompt).contains("【心证铁卷】").contains("【私密档案】");
    }

    @Test
    void buildsPersonaFromRoleJson() {
        Map<String, Object> role = roleJson();
        String persona = SystemPromptBuilder.buildPersona(role);

        assertThat(persona).contains("【绝对底线").contains("❌ 不提供违法犯罪的指导或信息");
        assertThat(persona).contains("## 角色：Luna");
        assertThat(persona).contains("**性格特质**：温柔、理性");
        assertThat(persona).contains("**核心原则**");
        assertThat(persona).contains("**昵称**：小明");
        assertThat(persona).contains("**沟通偏好**");
        assertThat(persona).contains("你对用户的承诺").contains("每周一提醒");
        assertThat(persona).contains("*Luna：陪你走过每一个平凡又特别的日子*");
    }

    @Test
    void emptySoulFallsBackToDefault() {
        String prompt = builder.build(SoulData.empty(), null, "", "web", 0);
        assertThat(prompt).isEqualTo("你是一个有帮助的AI助手，请用中文回答。");
    }

    @Test
    void nullSoulFallsBackToDefault() {
        assertThat(builder.build(null, null, "", "web", 0))
                .isEqualTo("你是一个有帮助的AI助手，请用中文回答。");
    }

    @Test
    void assembleWithStaticBase_equalsDirectBuild() {
        Map<String, Object> role = roleJson();
        for (String channel : List.of("web", "feishu_im")) {
            String direct = builder.build(soul(), role, "tool-overlay", channel, 8000);
            String staticBase = builder.buildStatic(soul(), channel, 8000);
            String assembled = builder.assemble(staticBase, role, "tool-overlay", soul(), channel);
            assertThat(assembled).isEqualTo(direct);
            assertThat(staticBase).contains("【主人铁律】").doesNotContain("tool-overlay");
        }
    }

    @Test
    void staticBaseDiffersByChannel() {
        String web = builder.buildStatic(soul(), "web", 8000);
        String im  = builder.buildStatic(soul(), "feishu_im", 8000);
        assertThat(web).contains("【心证铁卷】");
        assertThat(im).doesNotContain("【心证铁卷】");
        // 静态底座不含 whisper/tool_overlay（assemble 阶段才追加）
        assertThat(web).doesNotContain("【私密档案】").doesNotContain("tool-overlay");
    }

    private static Map<String, Object> roleJson() {
        Map<String, Object> role = new LinkedHashMap<>();
        role.put("role_id", "luna_companion");

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", "Luna");
        card.put("signature", "陪你走过每一个平凡又特别的日子");
        role.put("role_card", card);

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("personality", List.of("温柔", "理性"));
        identity.put("principles", List.of("真诚回应", "鼓励独立思考"));
        identity.put("redlines", List.of("不提供违法犯罪的指导或信息"));
        identity.put("language_style", "亲切自然");
        role.put("core_identity", identity);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("nickname", "小明");
        profile.put("relationship", "知心朋友");
        profile.put("background", "26岁软件工程师");
        profile.put("preferences", Map.of("tone", "casual"));
        profile.put("disclosed_info", List.of("在准备 PMP 考试"));
        role.put("user_profile", profile);

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("commitments", List.of(Map.of(
                "content", "每周一提醒小明喝水休息",
                "timestamp", "2026-06-01T10:00:00",
                "status", "active")));
        role.put("role_memory", memory);
        return role;
    }
}

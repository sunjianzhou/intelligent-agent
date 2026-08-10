package com.intelligent.agent.web.ai.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RulesSection 测试：解析、隐私分层、token 退化、缓存失效。
 */
class RulesSectionTest {

    private static final String RULES = """
            # 主人铁律

            ## 安全边界

            ### RULE-001: 不做危险操作
            - **版本**: v1
            - **状态**: 现行
            - **隐私等级**: public
            - **生效时间**: 2026-07-01
            - **具体诉求**: 任何情况下不得执行 rm -rf
            - **违反后果**: 数据丢失
            - **重要度**: ★★★★★

            ### RULE-002: 私密习惯
            - **版本**: v1
            - **状态**: 现行
            - **隐私等级**: private
            - **生效时间**: 2026-07-01
            - **具体诉求**: 每天 22:00 提醒用户休息
            - **重要度**: ★★★

            ### RULE-003: 已废止规则
            - **版本**: v1
            - **状态**: ~~已废止（2026-07-02）~~
            - **隐私等级**: public
            - **生效时间**: 2026-07-01
            - **具体诉求**: 旧要求
            - **重要度**: ★★★
            """;

    @Test
    void parsesActiveRulesAndSkipsDeprecated() {
        var entries = RulesSection.parse(RULES);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).id()).isEqualTo("RULE-001");
        assertThat(entries.get(0).priorityStars()).isEqualTo("★★★★★");
        assertThat(entries.get(1).privacy()).isEqualTo("private");
    }

    @Test
    void webChannelSeesPublicAndPrivate() {
        String section = RulesSection.build(RULES, "web", 8000);
        assertThat(section).contains("RULE-001").contains("RULE-002");
        assertThat(section).doesNotContain("RULE-003");
    }

    @Test
    void imChannelSeesOnlyPublic() {
        String section = RulesSection.build(RULES, "feishu_im", 8000);
        assertThat(section).contains("RULE-001");
        assertThat(section).doesNotContain("RULE-002");
    }

    @Test
    void degradedBudgetKeepsOnlyCritical() {
        String section = RulesSection.build(RULES, "web", 3000);
        assertThat(section).contains("RULE-001");
        assertThat(section).doesNotContain("RULE-002");
        assertThat(section).contains("token 预算紧张");
    }

    @Test
    void emptyRulesProduceEmptySection() {
        assertThat(RulesSection.build("", "web", 8000)).isEmpty();
        assertThat(RulesSection.build("   ", "feishu_im", 8000)).isEmpty();
    }

    @Test
    void cacheInvalidationForcesRebuild() {
        String first = RulesSection.build(RULES, "web", 8000);
        RulesSection.invalidateCache();
        String second = RulesSection.build(RULES, "web", 8000);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void allPrivateRulesExcludedFromIm() {
        String privateOnly = """
                ### RULE-010: 私密
                - **状态**: 现行
                - **隐私等级**: private
                - **具体诉求**: 不公开
                - **重要度**: ★★★
                """;
        assertThat(RulesSection.build(privateOnly, "feishu_im", 8000)).isEmpty();
        assertThat(RulesSection.build(privateOnly, "cli", 8000)).contains("RULE-010");
    }
}

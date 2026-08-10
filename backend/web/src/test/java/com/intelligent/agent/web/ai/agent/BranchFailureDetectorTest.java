package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分支失败检测测试（TODO-110 Task 4.4）。
 */
class BranchFailureDetectorTest {

    @Test
    void detectsSameToolErrorThreeTimes() {
        ToolResult error = ToolResult.error("connection refused");
        String reason = BranchFailureDetector.detectSameToolError(List.of(error, error, error));

        assertThat(reason).contains("same_tool_same_error").contains("3 次");
    }

    @Test
    void noFailureBelowThreshold() {
        ToolResult error = ToolResult.error("boom");
        assertThat(BranchFailureDetector.detectSameToolError(List.of(error, error))).isNull();
    }

    @Test
    void detectsConsecutiveDuplicateRounds() {
        List<String> texts = List.of(
                "需要检查数据库连接配置并修复连接超时问题",
                "需要检查数据库连接配置并修复连接超时问题",
                "需要检查数据库连接配置并修复连接超时问题");
        assertThat(BranchFailureDetector.detectConsecutiveDuplicate(texts))
                .contains("consecutive_duplicate");
    }

    @Test
    void distinctRoundsPass() {
        assertThat(BranchFailureDetector.detectConsecutiveDuplicate(
                List.of("第一轮完全不同", "第二轮完全不同"))).isNull();
    }

    @Test
    void detectsHardcodedDangerousCommands() {
        BranchFailureDetector detector = new BranchFailureDetector();

        assertThat(detector.checkRuleViolations("执行 rm -rf / 会删除数据"))
                .isNotEmpty();
        assertThat(detector.checkRuleViolations("正常回答，没有任何危险命令"))
                .isEmpty();
        assertThat(detector.checkRuleViolations("SELECT * FROM users WHERE id = 1"))
                .isEmpty();
    }

    @Test
    void extractsForbiddenPhrasesFromRulesText() {
        BranchFailureDetector detector = new BranchFailureDetector(
                "### RULE-001: 测试\n- **具体诉求**: 禁止泄露用户手机号给第三方\n");

        assertThat(detector.checkRuleViolations("回复中泄露用户手机号给第三方"))
                .isNotEmpty();
    }
}

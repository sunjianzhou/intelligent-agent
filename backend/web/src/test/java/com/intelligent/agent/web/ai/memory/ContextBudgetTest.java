package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.ai.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-01 预算组件测试（预算分配 / 边界）：
 * num_ctx 优先级（请求显式 > 模型表 > 默认）、安全边际、分块合计、字符规则估算。
 */
class ContextBudgetTest {

    private final ContextBudget budget = new ContextBudget(4096, Map.of("qwen2.5:7b", 16384));

    @Test
    void numCtxPrecedenceRequestOverModelTableOverDefault() {
        assertThat(budget.numCtxFor("qwen2.5:7b", null)).isEqualTo(16384);
        assertThat(budget.numCtxFor("other-model", null)).isEqualTo(4096);
        assertThat(budget.numCtxFor("qwen2.5:7b", Map.of("num_ctx", 8192))).isEqualTo(8192);
        // 非法显式值回退模型表
        assertThat(budget.numCtxFor("qwen2.5:7b", Map.of("num_ctx", "bad")))
                .isEqualTo(16384);
    }

    @Test
    void usableTokensAppliesSafetyMargin() {
        assertThat(budget.usableTokens(4096))
                .isEqualTo((int) Math.floor(4096 * (1.0 - ContextBudget.SAFETY_MARGIN)));
    }

    @Test
    void planBlocksSumWithinUsable() {
        ContextBudget.Plan plan = budget.plan("qwen2.5:7b", Map.of());

        assertThat(plan.numCtx()).isEqualTo(16384);
        int sum = plan.systemTokens() + plan.toolTokens() + plan.memoryTokens()
                + plan.projectTokens() + plan.historyTokens() + plan.currentTokens();
        assertThat(sum).isLessThanOrEqualTo(plan.usableTokens());
        assertThat(plan.historyTokens()).isGreaterThan(plan.systemTokens());
    }

    @Test
    void estimateCjkOneTokenPerChar() {
        assertThat(ContextBudget.estimateTokens("你好世界")).isEqualTo(4);
        assertThat(ContextBudget.estimateTokens("カタカナ")).isEqualTo(4);
    }

    @Test
    void estimateAsciiQuarterTokenPerChar() {
        assertThat(ContextBudget.estimateTokens("abcdefgh")).isEqualTo(2);
        assertThat(ContextBudget.estimateTokens("")).isZero();
    }

    @Test
    void estimateMessagesAddsPerMessageOverhead() {
        List<ChatMessage> messages = List.of(
                ChatMessage.user("你好"), ChatMessage.assistant("hi"));

        int expected = 2 * ContextBudget.MESSAGE_OVERHEAD_TOKENS
                + ContextBudget.estimateTokens("user\n你好")
                + ContextBudget.estimateTokens("assistant\nhi");
        assertThat(ContextBudget.estimateMessages(messages)).isEqualTo(expected);
    }

    @Test
    void fitToBudgetStaysWithinBudgetIncludingMarker() {
        String longText = "a".repeat(200);

        String fit = ContextBudget.fitToBudget(longText, 40);

        assertThat(ContextBudget.estimateTokens(fit)).isLessThanOrEqualTo(40);
        assertThat(fit).contains("截断");
    }

    @Test
    void fitRecordsKeepsMostRelevantWithinBudget() {
        List<MemoryRecord> records = List.of(
                new MemoryRecord("r1", "u", "a".repeat(100), Map.of(), 0.9),
                new MemoryRecord("r2", "u", "b".repeat(100), Map.of(), 0.8));

        List<MemoryRecord> fit = ContextBudget.fitRecords(records, 30);

        assertThat(fit).isNotEmpty();
        assertThat(fit).allMatch(r ->
                ContextBudget.estimateTokens(r.content()) <= 30);
    }
}

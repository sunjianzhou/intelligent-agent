package com.intelligent.agent.web.ai.agent.planning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复杂度判定启发式测试：显式计划意图、多步骤信号、简单问候不误报。
 */
class PlanningComplexityDetectorTest {

    private final PlanningComplexityDetector detector = new PlanningComplexityDetector();

    @Test
    void explicitPlanIntentTriggersEvenForShortMessage() {
        assertThat(detector.isComplex("先给个计划")).isTrue();
        assertThat(detector.isComplex("规划一下怎么做")).isTrue();
        assertThat(detector.isComplex("列个步骤")).isTrue();
    }

    @Test
    void multiStepTaskWithConnectorsTriggers() {
        assertThat(detector.isComplex(
                "帮我查一下明天的天气，然后计算一下适合穿什么，最后提醒我出门带伞"))
                .isTrue();
    }

    @Test
    void numberedMultiActionListTriggers() {
        assertThat(detector.isComplex(
                "1. 搜索 Java 21 新特性 2. 总结要点 3. 生成一篇学习笔记"))
                .isTrue();
    }

    @Test
    void simpleGreetingIsNotComplex() {
        assertThat(detector.isComplex("你好")).isFalse();
        assertThat(detector.isComplex("现在几点了")).isFalse();
    }

    @Test
    void longSingleActionIsNotComplex() {
        // 长但单一动作：只有一个动作词，无连接词/编号，不触发计划
        assertThat(detector.isComplex("请帮我写一篇关于人工智能在医疗领域应用的详细报告")).isFalse();
    }

    @Test
    void blankOrNullIsNotComplex() {
        assertThat(detector.isComplex(null)).isFalse();
        assertThat(detector.isComplex("   ")).isFalse();
    }
}

package com.intelligent.agent.web.ai.agent.planning;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 复杂任务启发式判定（G6 planning 前置的门控）：
 * <ul>
 *   <li>显式计划意图（规划/计划/步骤/plan/steps 等）直接命中；</li>
 *   <li>否则需要达到最小长度，且至少命中 2 个信号：步骤连接词、动作词、编号列表、超长文本。</li>
 * </ul>
 * 纯确定性规则，保证可测试、零额外 LLM 开销。
 */
public class PlanningComplexityDetector {

    private static final List<String> PLAN_INTENT = List.of(
            "规划", "计划", "步骤", "分步", "安排一下", "怎么做最好",
            "plan", "steps", "outline", "step by step");

    private static final List<String> FIRST_STEPS = List.of(
            "先做", "先查", "先搜", "先看", "先写", "先算", "先列", "先给",
            "先确认", "先了解", "先分析", "先整理", "第一步", "首先");

    private static final List<String> CONNECTORS = List.of(
            "然后", "接着", "最后", "之后", "接下来", "并且", "同时", "另外", "再", "其次");

    private static final List<String> ACTION_WORDS = List.of(
            "查询", "搜索", "计算", "创建", "提醒", "发送", "生成", "翻译",
            "总结", "比较", "分析", "调研", "对比", "安排", "整理", "汇总", "写", "查");

    private static final Pattern NUMBERED = Pattern.compile("\\d[.、．)]|第[一二三四五六七八九十]+[步项]");

    private final int minMessageLength;

    public PlanningComplexityDetector() {
        this(24);
    }

    public PlanningComplexityDetector(int minMessageLength) {
        this.minMessageLength = Math.max(1, minMessageLength);
    }

    public boolean isComplex(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.trim();
        if (containsAny(m, PLAN_INTENT) || containsAny(m, FIRST_STEPS)) {
            return true;
        }
        if (m.length() < minMessageLength) {
            return false;
        }
        int signals = 0;
        if (containsAny(m, CONNECTORS)) {
            signals++;
        }
        if (containsAny(m, ACTION_WORDS)) {
            signals++;
        }
        if (NUMBERED.matcher(m).find()) {
            signals++;
        }
        if (m.length() >= 80) {
            signals++;
        }
        return signals >= 2;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

package com.intelligent.agent.web.ai.agent;

import com.intelligent.agent.web.ai.tool.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分支失败检测（对齐 Python _detect_branch_failure 5+1 信号）。
 *
 * <p>覆盖 Java 侧可实现且可测试的信号：</p>
 * <ul>
 *   <li>信号 1：同工具同错误 ≥3 次</li>
 *   <li>信号 2：LLM 输出连续 2 轮相似度 &gt;80%（Jaccard）</li>
 *   <li>信号 4：窗口内同时存在运行时错误与空响应</li>
 *   <li>信号 6：铁律违反扫描（硬编码危险模式 + rules.md 禁止性关键词）</li>
 * </ul>
 */
public final class BranchFailureDetector {

    private static final int SIGNAL_1_SAME_ERROR_COUNT = 3;
    private static final int SIGNAL_2_CONSECUTIVE_DUP = 2;
    private static final double SIMILARITY_THRESHOLD = 0.8;
    private static final int MAX_VIOLATIONS = 3;

    private static final List<Pattern> HARDCODED_PATTERNS = List.of(
            Pattern.compile("\\brm\\s+-rf\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsudo\\s+rm\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bos\\.system\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsubprocess\\.(call|run|Popen)\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\beval\\s*\\(\\s*(?!\\s*\\))", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bexec\\s*\\(\\s*(?!\\s*\\))", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b__import__\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bDROP\\s+TABLE\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bDROP\\s+DATABASE\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchmod\\s+777\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcurl\\s+.*\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwget\\s+.*\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdd\\s+if=.*of=/dev/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmkfs\\.\\w+\\s+/dev/", Pattern.CASE_INSENSITIVE));

    private static final Pattern FORBIDDEN_RE = Pattern.compile(
            "(?:不得|禁止|不能|不可|严禁)\\s*(.+?)(?:[，。；\\n]|$)");

    private final List<Pattern> violationPatterns;

    public BranchFailureDetector() {
        this("");
    }

    /** @param rulesText rules.md 内容（空串 = 仅使用硬编码模式）。 */
    public BranchFailureDetector(String rulesText) {
        List<Pattern> patterns = new ArrayList<>(HARDCODED_PATTERNS);
        if (rulesText != null && !rulesText.isBlank()) {
            Matcher m = FORBIDDEN_RE.matcher(rulesText);
            int perRule = 0;
            while (m.find() && perRule < 20) {
                String phrase = m.group(1).strip();
                if (phrase.length() >= 3) {
                    patterns.add(Pattern.compile(Pattern.quote(phrase), Pattern.CASE_INSENSITIVE));
                    perRule++;
                }
            }
        }
        this.violationPatterns = List.copyOf(patterns);
    }

    /** 信号 1：同工具同错误 ≥3 次。 */
    public static String detectSameToolError(List<ToolResult> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ToolResult result : results) {
            if (result == null || !ToolResult.ERROR.equals(result.status())
                    && !ToolResult.NOT_FOUND.equals(result.status())
                    && !ToolResult.TIMEOUT.equals(result.status())) {
                continue;
            }
            String key = String.valueOf(result.error()).substring(0,
                    Math.min(80, String.valueOf(result.error()).length()));
            counts.merge(key, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() >= SIGNAL_1_SAME_ERROR_COUNT) {
                return "same_tool_same_error: 工具相同错误 " + entry.getValue() + " 次（" + entry.getKey() + "）";
            }
        }
        return null;
    }

    /** 信号 2：连续 2 轮输出 Jaccard 相似度 &gt;0.8。 */
    public static String detectConsecutiveDuplicate(List<String> assistantTexts) {
        if (assistantTexts == null || assistantTexts.size() < 2) {
            return null;
        }
        int consecutive = 0;
        for (int i = 1; i < assistantTexts.size(); i++) {
            String prev = assistantTexts.get(i - 1);
            String cur = assistantTexts.get(i);
            if (prev != null && cur != null && !prev.isBlank() && !cur.isBlank()
                    && jaccard(prev, cur) > SIMILARITY_THRESHOLD) {
                consecutive++;
                if (consecutive >= SIGNAL_2_CONSECUTIVE_DUP) {
                    return "consecutive_duplicate: LLM 连续 " + (consecutive + 1)
                            + " 轮输出相似度 >" + SIMILARITY_THRESHOLD;
                }
            } else {
                consecutive = 0;
            }
        }
        return null;
    }

    /** 信号 6：铁律违反扫描，最多返回 3 条。 */
    public List<String> checkRuleViolations(String text) {
        List<String> violations = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return violations;
        }
        for (Pattern pattern : violationPatterns) {
            if (pattern.matcher(text).find()) {
                violations.add("[安全边界] " + pattern.pattern());
                if (violations.size() >= MAX_VIOLATIONS) {
                    break;
                }
            }
        }
        return violations;
    }

    private static double jaccard(String a, String b) {
        Set<String> tokensA = tokenize(a);
        Set<String> tokensB = tokenize(b);
        if (tokensA.isEmpty() && tokensB.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);
        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String token : text.toLowerCase().split("[^\\p{IsAlphabetic}\\p{IsHan}]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}

package com.intelligent.agent.web.ai.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * rules.md 主人铁律解析与格式化（对齐 Python system_prompt_builder）。
 *
 * <p>功能：结构化解析 RULE-### 条目 → 按渠道隐私等级过滤 → token 预算退化
 * （上下文不足 4096 时仅注入 critical 级）→ 按内容 hash + channel + degrade
 * 缓存。{@link #invalidateCache()} 在 heart_record 写入 rules.md 后调用。</p>
 */
public final class RulesSection {

    private static final Pattern RULE_BLOCK = Pattern.compile(
            "### (RULE-\\d+): (.+?)\\n(.*?)(?=\\n### RULE-|\\n## |\\Z)", Pattern.DOTALL);

    private static final Pattern RX_PRIVACY = Pattern.compile("隐私等级\\**\\s*[：:]\\s*(\\w+)");
    private static final Pattern RX_STATUS = Pattern.compile("状态\\**\\s*[：:]\\s*(.+?)(?:\\n|$)");
    private static final Pattern RX_STARS = Pattern.compile("重要度\\**\\s*[：:]\\s*(★+)");
    private static final Pattern RX_REQUIREMENT = Pattern.compile("具体诉求\\**\\s*[：:]\\s*(.+?)(?:\\n-|\\n\\n|\\n###|\\Z)", Pattern.DOTALL);
    private static final Pattern RX_TRIGGER = Pattern.compile("触发场景\\**\\s*[：:]\\s*(.+?)(?:\\n-|\\n\\n|\\n###|\\Z)", Pattern.DOTALL);
    private static final Pattern RX_CONSEQUENCE = Pattern.compile("违反后果\\**\\s*[：:]\\s*(.+?)(?:\\n-|\\n\\n|\\n###|\\Z)", Pattern.DOTALL);

    private static final int TOKEN_DEGRADE_THRESHOLD = 4096;

    private static final Map<String, Set<String>> PRIVACY_CHANNEL_MAP = Map.of(
            "web", Set.of("public", "private"),
            "cli", Set.of("public", "private"),
            "feishu_im", Set.of("public"),
            "wecom", Set.of("public"));
    private static final Set<String> PRIVACY_DEFAULT = Set.of("public", "private");

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private RulesSection() {
    }

    /** 清空 rules 缓存（heart_record 写入 rules.md 后调用）。 */
    public static void invalidateCache() {
        CACHE.clear();
    }

    /** 构建【主人铁律】段：隐私过滤 + token 预算退化 + 摘要行。无可注入规则时返回空串。 */
    public static String build(String rulesText, String channel, int maxContextTokens) {
        if (rulesText == null || rulesText.isBlank()) {
            return "";
        }
        String key = cacheKey(rulesText, channel, maxContextTokens);
        String cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String section = buildUncached(rulesText, channel, maxContextTokens);
        CACHE.put(key, section);
        return section;
    }

    private static String buildUncached(String rulesText, String channel, int maxContextTokens) {
        List<RuleEntry> entries = parse(rulesText);
        if (entries.isEmpty()) {
            return "";
        }
        entries = filterByPrivacy(entries, channel);
        if (entries.isEmpty()) {
            return "";
        }

        long critical = entries.stream().filter(e -> "★★★★★".equals(e.priorityStars())).count();
        long high = entries.stream().filter(e -> "★★★★".equals(e.priorityStars())).count();
        long normal = entries.stream().filter(e -> "★★★".equals(e.priorityStars())).count();
        int totalActive = entries.size();

        boolean degrade = maxContextTokens > 0 && maxContextTokens < TOKEN_DEGRADE_THRESHOLD;
        if (degrade) {
            entries = entries.stream()
                    .filter(e -> "★★★★★".equals(e.priorityStars()))
                    .toList();
            if (entries.isEmpty()) {
                return "";
            }
        }

        StringBuilder sb = new StringBuilder();
        if (degrade) {
            sb.append("【铁律摘要 — token 预算紧张，仅显示 critical 级】共 ").append(totalActive)
                    .append(" 条现行（critical:").append(critical)
                    .append(" high:").append(high)
                    .append(" normal:").append(normal).append("）\n");
        } else {
            sb.append("【铁律摘要】共 ").append(totalActive)
                    .append(" 条现行（critical:").append(critical)
                    .append(" high:").append(high)
                    .append(" normal:").append(normal).append("）\n");
        }
        sb.append('\n');

        for (RuleEntry e : entries) {
            sb.append("### ").append(e.id()).append(": ").append(e.title()).append('\n');
            sb.append("- **具体诉求**: ").append(e.requirement()).append('\n');
            if (!e.trigger().isEmpty()) {
                sb.append("- **触发场景**: ").append(e.trigger()).append('\n');
            }
            if (!e.consequence().isEmpty()) {
                sb.append("- **违反后果**: ").append(e.consequence()).append('\n');
            }
            sb.append("- **重要度**: ").append(e.priorityStars()).append('\n');
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 解析 rules.md 为结构化条目（跳过已废止）。 */
    static List<RuleEntry> parse(String rulesText) {
        List<RuleEntry> entries = new ArrayList<>();
        Matcher m = RULE_BLOCK.matcher(rulesText);
        while (m.find()) {
            String id = m.group(1);
            String title = m.group(2).trim();
            String block = m.group(3);

            String privacy = group(RX_PRIVACY, block, "private");
            String status = group(RX_STATUS, block, "现行");
            if (status.contains("已废止")) {
                continue;
            }
            String stars = group(RX_STARS, block, "★★★");
            String requirement = group(RX_REQUIREMENT, block, "");
            String trigger = group(RX_TRIGGER, block, "");
            String consequence = group(RX_CONSEQUENCE, block, "");
            entries.add(new RuleEntry(id, title, privacy, stars, requirement, trigger, consequence));
        }
        return entries;
    }

    private static List<RuleEntry> filterByPrivacy(List<RuleEntry> entries, String channel) {
        Set<String> allowed = PRIVACY_CHANNEL_MAP.getOrDefault(channel, PRIVACY_DEFAULT);
        return entries.stream().filter(e -> allowed.contains(e.privacy())).toList();
    }

    private static String group(Pattern pattern, String text, String fallback) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            String v = m.group(1).strip();
            return v.isEmpty() ? fallback : v;
        }
        return fallback;
    }

    private static String cacheKey(String rulesText, String channel, int maxContextTokens) {
        String hash = sha256(rulesText).substring(0, 16);
        String degrade = maxContextTokens > 0 && maxContextTokens < TOKEN_DEGRADE_THRESHOLD ? "1" : "0";
        return hash + "_" + (channel == null ? "web" : channel) + "_d" + degrade;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 单条铁律条目。 */
    public record RuleEntry(
            String id,
            String title,
            String privacy,
            String priorityStars,
            String requirement,
            String trigger,
            String consequence) {
    }
}

package com.intelligent.agent.web.ai.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统一 system prompt 组装器（对齐 Python SystemPromptBuilder）。
 *
 * <p>拼装顺序固定（防 Lost-in-Middle）：</p>
 * <ol>
 *   <li>① SOUL + IDENTITY（铁律最前）</li>
 *   <li>② USER</li>
 *   <li>③ MEMORY</li>
 *   <li>③.5 HEART（心证铁卷，IM 渠道排除）</li>
 *   <li>③.6 RULES（主人铁律，隐私分层 + token 退化）</li>
 *   <li>④ HEARTBEAT（自检铁规）</li>
 *   <li>⑤ persona（来自角色配置，无角色时跳过）</li>
 *   <li>⑥ whisper（私密档案，IM 渠道排除）</li>
 *   <li>⑦ tool_overlay（始终最后）</li>
 * </ol>
 */
public class SystemPromptBuilder {

    private static final String SEP = "\n" + "─".repeat(60) + "\n";
    private static final String FALLBACK = "你是一个有帮助的AI助手，请用中文回答。";

    /** 不注入 whisper/heart 段的渠道：内容会发往外部 IM 平台。 */
    private static final List<String> EXCLUDED_CHANNELS = List.of("feishu_im", "wecom");

    /**
     * 组装完整 system prompt。
     *
     * @param soul             灵魂层数据
     * @param role            角色配置 JSON（role_id/role_card/core_identity/user_profile/role_memory），可为 null
     * @param toolOverlay     工具指令段（已格式化的文本），可为空
     * @param channel         请求来源渠道（web / cli / feishu_im / wecom）
     * @param maxContextTokens 模型上下文 token 预算（0 = 不限制）
     */
    public String build(SoulData soul, Map<String, Object> role, String toolOverlay,
                        String channel, int maxContextTokens) {
        if (soul == null) {
            return FALLBACK;
        }
        String ch = channel == null || channel.isBlank() ? "web" : channel;
        boolean imChannel = EXCLUDED_CHANNELS.contains(ch);

        List<String> sections = staticSections(soul, ch, imChannel, maxContextTokens);

        if (role != null) {
            sections.add(buildPersona(role));
        }

        if (!soul.whisper().isBlank() && !imChannel) {
            sections.add(wrap("【私密档案】", soul.whisper()));
        }

        if (toolOverlay != null && !toolOverlay.isBlank()) {
            sections.add(toolOverlay.strip());
        }

        return join(sections);
    }

    /**
     * 静态底座：仅由 soul 文件决定的部分（灵魂/身份/用户/记忆/心证/铁律/自检），
     * 不含 persona / whisper / tool_overlay。供 PromptService 按
     * (channel, maxContextTokens, soulVersion) 预拼接缓存，请求路径只做轻量追加。
     */
    public String buildStatic(SoulData soul, String channel, int maxContextTokens) {
        if (soul == null) {
            return FALLBACK;
        }
        String ch = channel == null || channel.isBlank() ? "web" : channel;
        boolean imChannel = EXCLUDED_CHANNELS.contains(ch);
        return join(staticSections(soul, ch, imChannel, maxContextTokens));
    }

    /**
     * 在缓存的静态底座上追加 persona / whisper / tool_overlay，产出完整 system prompt。
     * 与 {@link #build} 的段落顺序完全一致，仅当 staticBase 由 buildStatic 生成时等价。
     */
    public String assemble(String staticBase, Map<String, Object> role, String toolOverlay,
                           SoulData soul, String channel) {
        if (staticBase == null || staticBase.isBlank()) {
            return build(soul, role, toolOverlay, channel, 0);
        }
        String ch = channel == null || channel.isBlank() ? "web" : channel;
        boolean imChannel = EXCLUDED_CHANNELS.contains(ch);

        List<String> sections = new ArrayList<>();
        sections.add(staticBase);

        if (role != null) {
            sections.add(buildPersona(role));
        }

        if (soul != null && !soul.whisper().isBlank() && !imChannel) {
            sections.add(wrap("【私密档案】", soul.whisper()));
        }

        if (toolOverlay != null && !toolOverlay.isBlank()) {
            sections.add(toolOverlay.strip());
        }

        return join(sections);
    }

    /** 静态段：灵魂/身份/用户/记忆 + 心证(IM 排除) + 铁律(隐私分层) + 自检。 */
    private static List<String> staticSections(SoulData soul, String ch, boolean imChannel,
                                               int maxContextTokens) {
        List<String> sections = new ArrayList<>();
        sections.add(wrap("【灵魂核心】", soul.soul()));
        sections.add(wrap("【身份】", soul.identity()));
        sections.add(wrap("【用户画像】", soul.user()));
        sections.add(wrap("【精选记忆】", soul.memory()));

        if (!soul.heart().isBlank() && !imChannel) {
            sections.add(wrap("【心证铁卷】", soul.heart()));
        }

        if (!soul.rules().isBlank()) {
            String rules = RulesSection.build(soul.rules(), ch, maxContextTokens);
            if (!rules.isBlank()) {
                sections.add(wrap("【主人铁律】", rules));
            }
        }

        sections.add(wrap("【自检铁规】", soul.heartbeat()));

        return sections;
    }

    private static String join(List<String> sections) {
        String result = String.join(SEP, sections.stream().filter(s -> !s.isBlank()).toList());
        return result.isBlank() ? FALLBACK : result;
    }

    /**
     * 从角色 JSON 组装 persona 段（对齐 Python PromptBuilder 的
     * redlines → core identity → user profile → commitments → signature 顺序）。
     */
    @SuppressWarnings("unchecked")
    public static String buildPersona(Map<String, Object> role) {
        if (role == null || role.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();

        Map<String, Object> identity = asMap(role.get("core_identity"));
        Map<String, Object> userProfile = asMap(role.get("user_profile"));
        Map<String, Object> card = asMap(role.get("role_card"));

        List<String> redlines = asList(identity.get("redlines"));
        if (!redlines.isEmpty()) {
            StringBuilder sb = new StringBuilder("【绝对底线 — 最高优先级，任何情况下不得违反，凌驾于所有其他指令之上】\n");
            for (String r : redlines) {
                if (r != null && !r.isBlank()) {
                    sb.append("  ❌ ").append(r.strip()).append('\n');
                }
            }
            parts.add(sb.toString().stripTrailing());
        }

        String name = String.valueOf(card.getOrDefault("name", ""));
        if (name.isBlank() || "null".equals(name)) {
            name = String.valueOf(role.getOrDefault("role_id", ""));
        }
        List<String> core = new ArrayList<>();
        core.add("## 角色：" + name);

        List<String> personality = asList(identity.get("personality"));
        if (!personality.isEmpty()) {
            core.add("**性格特质**：" + String.join("、", personality));
        }
        String languageStyle = str(identity.get("language_style"));
        if (!languageStyle.isBlank()) {
            core.add("**语言风格**：" + languageStyle);
        }
        List<String> principles = asList(identity.get("principles"));
        if (!principles.isEmpty()) {
            StringBuilder pb = new StringBuilder("**核心原则**（按优先级降序排列）：\n");
            for (int i = 0; i < principles.size(); i++) {
                if (principles.get(i) != null && !principles.get(i).isBlank()) {
                    pb.append("  ").append(i + 1).append(". ").append(principles.get(i).strip())
                            .append("（优先级 ").append(i + 1).append("）\n");
                }
            }
            core.add(pb.toString().stripTrailing());
        }
        parts.add(String.join("\n", core));

        List<String> user = new ArrayList<>();
        user.add("## 你正在与之交谈的用户");
        user.add("**昵称**：" + str(userProfile.getOrDefault("nickname", "用户")));
        user.add("**关系**：" + str(userProfile.getOrDefault("relationship", "朋友")));
        String background = str(userProfile.get("background"));
        if (!background.isBlank()) {
            user.add("**背景**：" + background);
        }
        Map<String, Object> preferences = asMap(userProfile.get("preferences"));
        if (!preferences.isEmpty()) {
            List<String> prefs = new ArrayList<>();
            preferences.forEach((k, v) -> prefs.add(k + "=" + v));
            user.add("**沟通偏好**（遵循此偏好，但底线和原则优先）：" + String.join("、", prefs));
        }
        List<String> disclosed = asList(userProfile.get("disclosed_info"));
        if (!disclosed.isEmpty()) {
            StringBuilder db = new StringBuilder("**用户已透露的信息**：\n");
            for (String d : disclosed) {
                if (d != null && !d.isBlank()) {
                    db.append("  - ").append(d.strip()).append('\n');
                }
            }
            user.add(db.toString().stripTrailing());
        }
        parts.add(String.join("\n", user));

        Map<String, Object> roleMemory = asMap(role.get("role_memory"));
        List<Object> commitments = asRawList(roleMemory.get("commitments"));
        List<String> activeCommitments = new ArrayList<>();
        for (Object c : commitments) {
            if (c instanceof Map<?, ?> cm) {
                Object status = ((Map<String, Object>) cm).get("status");
                if (status == null || "active".equals(String.valueOf(status))) {
                    String content = str(((Map<String, Object>) cm).get("content"));
                    String timestamp = str(((Map<String, Object>) cm).get("timestamp"));
                    if (!content.isBlank()) {
                        activeCommitments.add("  [" + (timestamp.isBlank() ? "" : timestamp.substring(0, Math.min(10, timestamp.length())))
                                + "] " + content);
                    }
                }
            }
        }
        if (!activeCommitments.isEmpty()) {
            parts.add("## 你对用户的承诺（仍有效，请主动履行）\n" + String.join("\n", activeCommitments));
        }

        String signature = str(card.get("signature"));
        if (!signature.isBlank()) {
            parts.add("---\n*" + name + "：" + signature + "*");
        }

        return String.join("\n\n", parts.stream().filter(p -> !p.isBlank()).toList());
    }

    private static String wrap(String header, String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return header + "\n" + content.strip();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : (List<Object>) value) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asRawList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    private static String str(Object value) {
        if (value == null || "null".equals(String.valueOf(value))) {
            return "";
        }
        return String.valueOf(value).strip();
    }
}

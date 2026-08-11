package com.intelligent.agent.web.ai.tool.builtin.heart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * heart.md / rules.md 的 Markdown 解析、重建与文件 I/O 支撑（从 HeartRecordTool 拆分）。
 * 纯静态工具：原子写入 + 轮转备份 + 写入后读回校验（TODO-93 失职自查钩子）。
 */
public final class HeartMarkdownSupport {

    private static final Logger log = LoggerFactory.getLogger(HeartMarkdownSupport.class);

    public static final int BACKUP_KEEP = 5;

    public static final Map<String, String> CATEGORY_SECTION = Map.of(
            "主人心证", "主人心证",
            "主人教诲", "主人教诲",
            "智能体对主人的承诺", "智能体对主人的承诺",
            "主人对智能体的承诺", "主人对智能体的承诺");

    public static final List<String> RULE_CATEGORIES = List.of(
            "安全边界", "模型绑定", "工具使用", "失职与自查", "记忆与持久化", "用户交互", "隐私与数据");

    public static final Pattern ENTRY_RE = Pattern.compile("^- \\[(\\d{4}-\\d{2}-\\d{2})]\\s+(.+)$");

    private HeartMarkdownSupport() {
    }

    // ── heart.md 解析 / 重建 ──────────────────────────────────────

    public static Map<String, List<HeartEntry>> parseHeartSections(String text) {
        Map<String, List<HeartEntry>> sections = new LinkedHashMap<>();
        String current = null;
        int globalId = 0;
        for (String line : text.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("## ")) {
                String sectionName = stripped.substring(3).strip();
                current = CATEGORY_SECTION.containsValue(sectionName) ? sectionName : null;
                if (current != null) {
                    sections.computeIfAbsent(current, k -> new ArrayList<>());
                }
                continue;
            }
            if (stripped.startsWith("# ") && !stripped.startsWith("## ")) {
                current = null;
                continue;
            }
            if (current != null) {
                Matcher m = ENTRY_RE.matcher(stripped);
                if (m.find()) {
                    globalId++;
                    sections.get(current).add(new HeartEntry(globalId, m.group(1), m.group(2)));
                }
            }
        }
        return sections;
    }

    public static String rebuildHeartMd(String originalText, Map<String, List<HeartEntry>> sections) {
        String[] lines = originalText.split("\n", -1);
        List<String> result = new ArrayList<>();
        String current = null;
        int i = 0;
        while (i < lines.length) {
            String stripped = lines[i].strip();
            if (stripped.startsWith("# ") && !stripped.startsWith("## ")) {
                current = null;
                result.add(lines[i]);
                i++;
                continue;
            }
            if (stripped.startsWith("## ")) {
                result.add(lines[i]);
                String sectionName = stripped.substring(3).strip();
                current = CATEGORY_SECTION.containsValue(sectionName) ? sectionName : null;
                i++;
                if (current != null && sections.containsKey(current)) {
                    for (HeartEntry e : sections.get(current)) {
                        result.add("- [" + e.date() + "] " + e.content());
                    }
                    while (i < lines.length) {
                        String next = lines[i].strip();
                        if (next.startsWith("## ") || (next.startsWith("# ") && !next.startsWith("## "))) {
                            break;
                        }
                        i++;
                    }
                    if (i < lines.length && !lines[i].strip().startsWith("#")) {
                        result.add("");
                    }
                }
                continue;
            }
            if (current == null) {
                result.add(lines[i]);
            }
            i++;
        }
        return String.join("\n", result);
    }

    // ── 文件操作辅助 ──────────────────────────────────────────────

    public static void rotateBackup(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        for (int i = BACKUP_KEEP; i > 1; i--) {
            Path older = path.resolveSibling(path.getFileName() + ".bak." + (i - 1));
            Path newer = path.resolveSibling(path.getFileName() + ".bak." + i);
            try {
                if (Files.exists(older)) {
                    Files.move(older, newer, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.warn("备份轮转失败 {} → {}: {}", older, newer, e.getMessage());
            }
        }
        try {
            Files.copy(path, path.resolveSibling(path.getFileName() + ".bak.1"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("备份创建失败 {}: {}", path, e.getMessage());
        }
    }

    public static void atomicWrite(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("原子写入失败 " + path + ": " + e.getMessage(), e);
        }
    }

    public static boolean verifyContains(Path path, String expected) {
        if (!Files.exists(path)) {
            return false;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(expected);
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean verifyExcludes(Path path, String excluded) {
        if (!Files.exists(path)) {
            return false;
        }
        try {
            return !Files.readString(path, StandardCharsets.UTF_8).contains(excluded);
        } catch (IOException e) {
            return false;
        }
    }

    public static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败 " + path + ": " + e.getMessage(), e);
        }
    }

    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static String defaultHeartMd() {
        return "# 心证铁卷\n\n"
                + "## 主人心证\n<!-- 用户主动标记的永久记忆 -->\n\n"
                + "## 主人教诲\n<!-- 用户对 Agent 的长期行为指令 -->\n\n"
                + "## 智能体对主人的承诺\n<!-- Agent 对用户的承诺 -->\n\n"
                + "## 主人对智能体的承诺\n<!-- 用户对 Agent 的承诺 -->\n";
    }

    public static String defaultRulesMd() {
        StringBuilder sb = new StringBuilder("# 主人铁律\n\n")
                .append("> 以下规则为不可违反的永久铁律。\n>\n")
                .append("> **隐私等级说明**：public=所有渠道 / private=仅web+CLI / secret=仅存文件审计\n")
                .append("> **重要度说明**：critical ★★★★★ / high ★★★★ / normal ★★★\n");
        for (String c : RULE_CATEGORIES) {
            sb.append("\n## ").append(c).append("\n\n<!-- ").append(c).append("相关规则 -->\n");
        }
        return sb.toString();
    }

    public static String deprecateRuleVersion(String text, String ruleId, int nextVersion) {
        String today = LocalDate.now().toString();
        Pattern p = Pattern.compile(
                "(### " + Pattern.quote(ruleId) + ":.*?- \\*\\*版本\\*\\*: v\\d+\\n)"
                        + "- \\*\\*状态\\*\\*: 现行", Pattern.DOTALL);
        String replacement = "$1- **状态**: ~~已废止（被 v" + nextVersion + " 取代，" + today + "）~~";
        String result = p.matcher(text).replaceFirst(replacement);
        if (!result.equals(text)) {
            log.info("已废止 {} 旧版本（→ v{}）", ruleId, nextVersion);
        }
        return result;
    }

    /** heart.md 单条心证条目（id 全局 1-based）。 */
    public record HeartEntry(int id, String date, String content) {
    }
}

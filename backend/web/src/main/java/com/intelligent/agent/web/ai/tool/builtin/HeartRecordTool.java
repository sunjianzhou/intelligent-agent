package com.intelligent.agent.web.ai.tool.builtin;

import static com.intelligent.agent.web.ai.tool.builtin.heart.HeartMarkdownSupport.*;

import com.intelligent.agent.web.ai.prompt.RulesSection;
import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * heart_record 工具 — 读写 soul/heart.md 心证铁卷 + soul/rules.md 主人铁律。
 *
 * <p>对齐 Python heart_record.py：三个心证 action（append/list/delete）+
 * 五个铁律 action（rule_add/rule_list/rule_delete/rule_validate/rule_rollback）。
 * 写入前自动做 .bak.1~.bak.5 轮转备份，写入后读回校验（TODO-93 失职自查钩子）。
 * 只允许操作 soul/heart.md 与 soul/rules.md，不触及同目录其他文件。</p>
 */
@Slf4j
public class HeartRecordTool implements AgentTool {

    private static final Map<String, String> RULE_PRIORITIES = Map.of(
            "critical", "★★★★★", "high", "★★★★", "normal", "★★★");

    private static final Set<String> RULE_PRIVACY_LEVELS = Set.of("public", "private", "secret");

    private static final Pattern RULE_VERSION_RE = Pattern.compile(
            "### (RULE-\\d+):.*?\\n- \\*\\*版本\\*\\*: v(\\d+)", Pattern.DOTALL);
    private static final Pattern RULE_LIST_RE = Pattern.compile(
            "### (RULE-\\d+): (.+?)\\n"
                    + "- \\*\\*版本\\*\\*: v(\\d+)\\n"
                    + "- \\*\\*状态\\*\\*: (.+?)\\n"
                    + "- \\*\\*隐私等级\\*\\*: (\\w+)\\n"
                    + "- \\*\\*生效时间\\*\\*: (.+?)\\n"
                    + "- \\*\\*具体诉求\\*\\*: (.+?)\\n", Pattern.DOTALL);
    private static final Pattern STARS_RE = Pattern.compile("重要度\\**\\s*[：:]\\s*(★+)");
    private static final Pattern SECTION_HEADER_RE = Pattern.compile("## (.+)");

    private final Path soulDir;
    private final Path heartPath;
    private final Path rulesPath;
    private final Object fileLock = new Object();

    public HeartRecordTool(Path soulDir) {
        this.soulDir = soulDir.toAbsolutePath().normalize();
        this.heartPath = this.soulDir.resolve("heart.md");
        this.rulesPath = this.soulDir.resolve("rules.md");
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "heart_record",
                "心证铁卷管理工具，用于在 soul/heart.md 中增/查/删心证条目，并在 soul/rules.md 中增/查/删主人铁律。"
                        + "支持的 action: append(追加心证), list(列出心证), delete(删除心证), "
                        + "rule_add(新增铁律), rule_list(列出铁律), rule_delete(废止铁律), "
                        + "rule_validate(校验铁律), rule_rollback(回滚铁律)。写入前自动轮转备份。",
                false, null, null,
                Map.ofEntries(
                        Map.entry("type", "object"),
                        Map.entry("properties", Map.ofEntries(
                                Map.entry("action", Map.of("type", "string",
                                        "enum", List.of("append", "list", "delete", "rule_add",
                                                "rule_list", "rule_delete", "rule_validate", "rule_rollback"))),
                                Map.entry("content", Map.of("type", "string",
                                        "description", "心证内容（append 时必填）")),
                                Map.entry("category", Map.of("type", "string",
                                        "description", "分区：如 principle/experience/insight 等")),
                                Map.entry("tags", Map.of("type", "string",
                                        "description", "逗号分隔标签")),
                                Map.entry("weight", Map.of("type", "string",
                                        "enum", List.of("normal", "important", "critical"),
                                        "description", "心证权重，默认 normal")),
                                Map.entry("id", Map.of("type", "string",
                                        "description", "心证条目 ID（delete 时必填）")),
                                Map.entry("rule_id", Map.of("type", "string",
                                        "description", "铁律 ID（rule_delete 时必填）")),
                                Map.entry("rule_title", Map.of("type", "string",
                                        "description", "铁律标题（rule_add）")),
                                Map.entry("rule_requirement", Map.of("type", "string",
                                        "description", "铁律要求（rule_add）")),
                                Map.entry("rule_trigger", Map.of("type", "string",
                                        "description", "触发条件（rule_add）")),
                                Map.entry("rule_consequence", Map.of("type", "string",
                                        "description", "违反后果（rule_add）")),
                                Map.entry("rule_priority", Map.of("type", "string",
                                        "description", "优先级（rule_add）")),
                                Map.entry("rule_privacy", Map.of("type", "string",
                                        "description", "隐私级别（rule_add）")))),
                        Map.entry("required", List.of("action"))));
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        String action = str(arguments.get("action"));
        synchronized (fileLock) {
            switch (action == null ? "" : action) {
                case "append":
                    return doAppend(arguments);
                case "list":
                    return doList(arguments);
                case "delete":
                    return doDelete(arguments);
                case "rule_add":
                    return doRuleAdd(arguments);
                case "rule_list":
                    return doRuleList(arguments);
                case "rule_delete":
                    return doRuleDelete(arguments);
                case "rule_validate":
                    return validateRules();
                case "rule_rollback":
                    return ruleRollback(arguments);
                default:
                    return Map.of("error", "未知 action: " + action
                            + "，支持 append / list / delete / rule_add / rule_list / rule_delete / rule_validate / rule_rollback");
            }
        }
    }

    // ── 心证 append ────────────────────────────────────────────────

    private Map<String, Object> doAppend(Map<String, Object> args) {
        String content = str(args.get("content"));
        if (content.isBlank()) {
            return Map.of("error", "content 不能为空");
        }
        String category = str(args.get("category"));
        String section = CATEGORY_SECTION.get(category);
        if (section == null) {
            return Map.of("error", "无效分区 '" + category + "'，有效分区: " + CATEGORY_SECTION.keySet());
        }

        rotateBackup(heartPath);
        String text = Files.exists(heartPath) ? readText(heartPath) : defaultHeartMd();
        String today = LocalDate.now().toString();

        StringBuilder entry = new StringBuilder("- [" + today + "] " + content.strip());
        String tags = str(args.get("tags"));
        String weight = str(args.get("weight"));
        List<String> extra = new ArrayList<>();
        if (!tags.isBlank()) {
            extra.add("tags=" + tags.strip());
        }
        if (!weight.isBlank() && !"normal".equals(weight)) {
            extra.add("weight=" + weight);
        }
        if (!extra.isEmpty()) {
            entry.append("  <!-- ").append(String.join(", ", extra)).append(" -->");
        }

        String newText = appendToSection(text, section, entry.toString());
        atomicWrite(heartPath, newText);
        if (!verifyContains(heartPath, content.strip())) {
            return Map.of("error", "写入后验证失败：文件中未找到预期内容");
        }
        log.info("heart_record append → {}: {}", section, content.strip().substring(0, Math.min(60, content.strip().length())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("action", "append");
        result.put("section", section);
        result.put("date", today);
        result.put("content", content.strip());
        return result;
    }

    private static String appendToSection(String text, String section, String entryLine) {
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>();
        boolean found = false;
        for (int i = 0; i < lines.length; i++) {
            out.add(lines[i]);
            String stripped = lines[i].strip();
            if (stripped.startsWith("## ") && stripped.substring(3).strip().equals(section)) {
                found = true;
                i++;
                while (i < lines.length
                        && (lines[i].isBlank() || lines[i].strip().startsWith("<!--"))) {
                    out.add(lines[i]);
                    i++;
                }
                out.add(entryLine);
                while (i < lines.length) {
                    out.add(lines[i]);
                    i++;
                }
                break;
            }
        }
        if (found) {
            return String.join("\n", out);
        }
        String tail = text.endsWith("\n") ? text : text + "\n";
        return tail + "\n## " + section + "\n\n" + entryLine + "\n";
    }

    // ── 心证 list ─────────────────────────────────────────────────

    private Map<String, Object> doList(Map<String, Object> args) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!Files.exists(heartPath)) {
            result.put("entries", List.of());
            result.put("total", 0);
            return result;
        }
        Map<String, List<HeartEntry>> sections = parseHeartSections(readText(heartPath));
        String category = str(args.get("category"));
        List<HeartEntry> entries;
        if (!category.isBlank()) {
            String section = CATEGORY_SECTION.get(category);
            if (section == null) {
                return Map.of("error", "无效分区 '" + category + "'");
            }
            entries = sections.getOrDefault(section, List.of());
        } else {
            entries = new ArrayList<>();
            sections.values().forEach(entries::addAll);
        }
        List<Map<String, Object>> list = entries.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("date", e.date());
            m.put("content", e.content());
            return m;
        }).toList();
        result.put("entries", list);
        result.put("total", list.size());
        return result;
    }

    // ── 心证 delete ───────────────────────────────────────────────

    private Map<String, Object> doDelete(Map<String, Object> args) {
        String idStr = str(args.get("id"));
        if (idStr.isBlank()) {
            return Map.of("error", "id 不能为空（使用 list 查看各条目的 id）");
        }
        if (!Files.exists(heartPath)) {
            return Map.of("error", "heart.md 不存在，没有可删除的条目");
        }
        int targetId;
        try {
            targetId = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            return Map.of("error", "无效 id: " + idStr + "，必须为整数");
        }

        rotateBackup(heartPath);
        String text = readText(heartPath);
        Map<String, List<HeartEntry>> sections = parseHeartSections(text);

        HeartEntry target = null;
        String targetSection = null;
        for (Map.Entry<String, List<HeartEntry>> e : sections.entrySet()) {
            for (HeartEntry entry : e.getValue()) {
                if (entry.id() == targetId) {
                    target = entry;
                    targetSection = e.getKey();
                    break;
                }
            }
            if (target != null) {
                break;
            }
        }
        if (target == null) {
            return Map.of("error", "未找到 id=" + targetId + " 的心证条目");
        }

        List<HeartEntry> remaining = sections.get(targetSection).stream()
                .filter(e -> e.id() != targetId)
                .toList();
        sections.put(targetSection, remaining);
        String newText = rebuildHeartMd(text, sections);
        atomicWrite(heartPath, newText);
        if (!verifyExcludes(heartPath, target.content())) {
            return Map.of("error", "删除后验证失败：文件中仍包含已删除内容");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("action", "delete");
        result.put("id", targetId);
        result.put("section", targetSection);
        result.put("deleted_content", target.content());
        return result;
    }

    // ── 铁律 rule_add ─────────────────────────────────────────────

    private Map<String, Object> doRuleAdd(Map<String, Object> args) {
        String ruleId = str(args.get("rule_id")).strip().toUpperCase();
        String title = str(args.get("rule_title")).strip();
        String category = str(args.get("category")).strip();
        String requirement = str(args.get("rule_requirement")).strip();
        String trigger = str(args.get("rule_trigger")).strip();
        String consequence = str(args.get("rule_consequence")).strip();
        String priority = str(args.get("rule_priority")).strip();
        String privacy = str(args.get("rule_privacy")).strip();
        if (priority.isBlank()) {
            priority = "normal";
        }
        if (privacy.isBlank()) {
            privacy = "private";
        }

        List<String> missing = new ArrayList<>();
        if (ruleId.isBlank()) missing.add("rule_id");
        if (title.isBlank()) missing.add("rule_title");
        if (category.isBlank()) missing.add("rule_category");
        if (requirement.isBlank()) missing.add("rule_requirement");
        if (!missing.isEmpty()) {
            return Map.of("error", "缺少必填字段: " + String.join(", ", missing));
        }
        if (!RULE_CATEGORIES.contains(category)) {
            return Map.of("error", "无效的作用分类 '" + category + "'，可选: " + RULE_CATEGORIES);
        }
        if (!RULE_PRIORITIES.containsKey(priority)) {
            return Map.of("error", "无效的重要度 '" + priority + "'，可选: " + RULE_PRIORITIES.keySet());
        }
        if (!RULE_PRIVACY_LEVELS.contains(privacy)) {
            return Map.of("error", "无效的隐私等级 '" + privacy + "'，可选: " + RULE_PRIVACY_LEVELS);
        }

        String existingText = Files.exists(rulesPath) ? readText(rulesPath) : defaultRulesMd();

        // 幂等检测：同 ID + 同 title + 同 requirement + 现行 → 拒绝
        Pattern dup = Pattern.compile(
                "### " + Pattern.quote(ruleId) + ": " + Pattern.quote(title) + "\\n"
                        + ".*?现行.*?" + Pattern.quote(requirement), Pattern.DOTALL);
        if (dup.matcher(existingText).find()) {
            return Map.of("error", "铁律 " + ruleId + "「" + title
                    + "」已存在且为现行版本，内容相同。如需修改请说明变更内容，我会升级版本号。");
        }

        List<Integer> versions = new ArrayList<>();
        Matcher vm = RULE_VERSION_RE.matcher(existingText);
        while (vm.find()) {
            if (ruleId.equals(vm.group(1))) {
                versions.add(Integer.parseInt(vm.group(2)));
            }
        }
        int newVersion = versions.isEmpty() ? 1 : versions.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;

        List<String> conflicts = checkRuleConflicts(ruleId, title, requirement, existingText);
        if (!versions.isEmpty()) {
            existingText = deprecateRuleVersion(existingText, ruleId, newVersion);
        }

        rotateBackup(rulesPath);
        String stars = RULE_PRIORITIES.get(priority);
        String today = LocalDate.now().toString();
        StringBuilder entry = new StringBuilder();
        entry.append("\n### ").append(ruleId).append(": ").append(title).append("\n");
        entry.append("- **版本**: v").append(newVersion).append("\n");
        entry.append("- **状态**: 现行\n");
        entry.append("- **隐私等级**: ").append(privacy).append("\n");
        entry.append("- **生效时间**: ").append(today).append("\n");
        entry.append("- **具体诉求**: ").append(requirement).append("\n");
        if (!trigger.isBlank()) {
            entry.append("- **触发场景**: ").append(trigger).append("\n");
        }
        if (!consequence.isBlank()) {
            entry.append("- **违反后果**: ").append(consequence).append("\n");
        }
        entry.append("- **重要度**: ").append(stars).append("\n");

        String sectionHeader = "## " + category;
        String base = existingText.contains(sectionHeader) ? existingText : existingText.stripTrailing() + "\n" + sectionHeader + "\n";
        String newText = base.stripTrailing() + entry + "\n";
        atomicWrite(rulesPath, newText);
        RulesSection.invalidateCache();

        if (!verifyContains(rulesPath, ruleId + ": " + title)) {
            return Map.of("error", "写入后验证失败：文件中未找到新增的规则条目");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("action", "rule_add");
        result.put("rule_id", ruleId);
        result.put("version", newVersion);
        result.put("category", category);
        result.put("priority", priority);
        result.put("privacy", privacy);
        if (!conflicts.isEmpty()) {
            result.put("conflicts", conflicts);
            result.put("warning", "铁律已追加，但检测到 " + conflicts.size() + " 个潜在冲突，请确认是否合理");
            log.warn("规则冲突检测 ({}): {}", ruleId, conflicts);
        }
        log.info("heart_record rule_add → {} v{}: {}", ruleId, newVersion, title);
        return result;
    }

    // ── 铁律 rule_list ────────────────────────────────────────────

    private Map<String, Object> doRuleList(Map<String, Object> args) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!Files.exists(rulesPath)) {
            result.put("entries", List.of());
            result.put("total", 0);
            result.put("active", 0);
            result.put("deprecated", 0);
            return result;
        }
        String text = readText(rulesPath);
        List<Map<String, Object>> entries = new ArrayList<>();
        Matcher m = RULE_LIST_RE.matcher(text);
        while (m.find()) {
            String rid = m.group(1);
            String title = m.group(2);
            int version = Integer.parseInt(m.group(3));
            String status = m.group(4).strip();
            String privacy = m.group(5);
            String effectiveDate = m.group(6).strip();
            String requirement = m.group(7).strip();

            int blockStart = m.start();
            int blockEnd = text.indexOf("### RULE-", m.end());
            if (blockEnd == -1) {
                blockEnd = text.length();
            }
            String block = text.substring(blockStart, blockEnd);
            Matcher sm = STARS_RE.matcher(block);
            String stars = sm.find() ? sm.group(1) : "";

            String before = text.substring(0, blockStart);
            Matcher cm = SECTION_HEADER_RE.matcher(before);
            String category = "";
            while (cm.find()) {
                category = cm.group(1).strip();
            }

            boolean active = status.contains("现行") && !status.contains("已废止");
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", rid);
            e.put("title", title);
            e.put("version", version);
            e.put("status", active ? "active" : "deprecated");
            e.put("privacy", privacy);
            e.put("category", category);
            e.put("effective_date", effectiveDate);
            e.put("requirement", requirement.length() > 120 ? requirement.substring(0, 120) : requirement);
            e.put("priority", stars);
            entries.add(e);
        }

        String filter = str(args.get("category"));
        if (!filter.isBlank()) {
            entries = entries.stream().filter(e -> filter.equals(e.get("category"))).toList();
        }
        long activeCount = entries.stream().filter(e -> "active".equals(e.get("status"))).count();
        result.put("entries", entries);
        result.put("total", entries.size());
        result.put("active", activeCount);
        result.put("deprecated", entries.size() - activeCount);
        return result;
    }

    // ── 铁律 rule_delete（软删除） ─────────────────────────────────

    private Map<String, Object> doRuleDelete(Map<String, Object> args) {
        String ruleId = str(args.get("rule_id")).strip().toUpperCase();
        if (ruleId.isBlank()) {
            return Map.of("error", "rule_id 不能为空");
        }
        if (!Files.exists(rulesPath)) {
            return Map.of("error", "rules.md 不存在");
        }
        String text = readText(rulesPath);
        Pattern p = Pattern.compile("(### " + Pattern.quote(ruleId) + ":.*?- \\*\\*状态\\*\\*: )现行", Pattern.DOTALL);
        if (!p.matcher(text).find()) {
            return Map.of("error", "未找到现行版本的 " + ruleId);
        }
        rotateBackup(rulesPath);
        String today = LocalDate.now().toString();
        String newText = p.matcher(text).replaceFirst("$1~~已废止（" + today + "）~~");
        atomicWrite(rulesPath, newText);
        RulesSection.invalidateCache();
        log.info("heart_record rule_delete → {} 已废止", ruleId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("action", "rule_delete");
        result.put("rule_id", ruleId);
        result.put("date", today);
        return result;
    }

    // ── 铁律 validate / rollback ─────────────────────────────────

    private Map<String, Object> validateRules() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!Files.exists(rulesPath)) {
            result.put("ok", false);
            result.put("error", "rules.md 不存在");
            return result;
        }
        String text = readText(rulesPath);
        Set<String> found = new java.util.HashSet<>();
        Set<String> deprecated = new java.util.HashSet<>();
        Matcher m = Pattern.compile("### (RULE-\\d+):").matcher(text);
        while (m.find()) {
            String rid = m.group(1);
            found.add(rid);
            int next = text.indexOf("### RULE-", m.end());
            int end = next == -1 ? text.length() : next;
            if (text.substring(m.start(), end).contains("已废止")) {
                deprecated.add(rid);
            }
        }
        Set<String> active = new java.util.HashSet<>(found);
        active.removeAll(deprecated);
        List<String> missing = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            String id = String.format("RULE-%03d", i);
            if (!active.contains(id)) {
                missing.add(id);
            }
        }
        result.put("ok", missing.isEmpty());
        result.put("total_in_file", found.size());
        result.put("active", active.size());
        result.put("deprecated", deprecated.stream().sorted().toList());
        result.put("missing", missing);
        return result;
    }

    private Map<String, Object> ruleRollback(Map<String, Object> args) {
        int n;
        try {
            n = Integer.parseInt(str(args.getOrDefault("bak_n", "1")));
        } catch (NumberFormatException e) {
            n = 1;
        }
        Path bak = rulesPath.resolveSibling("rules.md.bak." + n);
        if (!Files.exists(bak)) {
            return Map.of("error", "回滚失败：备份文件不存在 " + bak);
        }
        synchronized (fileLock) {
            try {
                if (Files.exists(rulesPath)) {
                    Files.copy(rulesPath, rulesPath.resolveSibling("rules.md.bak.0"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                Files.copy(bak, rulesPath, StandardCopyOption.REPLACE_EXISTING);
                RulesSection.invalidateCache();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("ok", true);
                result.put("action", "rule_rollback");
                result.put("bak_n", n);
                return result;
            } catch (IOException e) {
                return Map.of("error", "回滚失败: " + e.getMessage());
            }
        }
    }

    // ── 冲突检测 ──────────────────────────────────────────────────

    private static List<String> checkRuleConflicts(String ruleId, String title,
                                                   String requirement, String existingText) {
        List<String> conflicts = new ArrayList<>();
        Matcher m = Pattern.compile(
                "### (RULE-\\d+): (.+?)\\n.*?具体诉求\\**\\s*[：:]\\s*(.+?)(?:\\n-|$)",
                Pattern.DOTALL).matcher(existingText);
        while (m.find()) {
            String existId = m.group(1);
            String existTitle = m.group(2);
            String existReq = m.group(3).strip();
            if (timeConstraintConflict(requirement, existReq)) {
                conflicts.add("时间约束冲突: " + ruleId + "「" + title + "」vs "
                        + existId + "「" + existTitle + "」——两者对同一时间段的要求矛盾");
            }
            if (modelBindingConflict(requirement, existReq)) {
                conflicts.add("模型绑定冲突: " + ruleId + "「" + title + "」vs "
                        + existId + "「" + existTitle + "」——两者指定了不同的强制模型");
            }
            if (behaviorConflict(requirement, existReq)) {
                conflicts.add("行为指令冲突: " + ruleId + "「" + title + "」vs "
                        + existId + "「" + existTitle + "」——两者对同一行为的要求相反");
            }
        }
        return conflicts;
    }

    private static boolean timeConstraintConflict(String reqA, String reqB) {
        boolean hasMustA = reqA.contains("必须");
        boolean hasCannotB = reqB.contains("不能") || reqB.contains("禁止");
        boolean hasMustB = reqB.contains("必须");
        boolean hasCannotA = reqA.contains("不能") || reqA.contains("禁止");
        return (hasMustA && hasCannotB) || (hasMustB && hasCannotA);
    }

    private static boolean modelBindingConflict(String reqA, String reqB) {
        Set<String> modelsA = extractModels(reqA);
        Set<String> modelsB = extractModels(reqB);
        if (modelsA.isEmpty() || modelsB.isEmpty()) {
            return false;
        }
        return !modelsA.equals(modelsB);
    }

    private static Set<String> extractModels(String req) {
        Set<String> models = new java.util.HashSet<>();
        Matcher m = Pattern.compile("(?:必须|只能|强制).*?(?:使用|绑定|用)\\s*(\\S+)").matcher(req);
        while (m.find()) {
            models.add(m.group(1));
        }
        return models;
    }

    private static boolean behaviorConflict(String reqA, String reqB) {
        Set<String> mustA = new java.util.HashSet<>();
        Matcher ma = Pattern.compile("必须(.+?)(?:[，。；]|$)").matcher(reqA);
        while (ma.find()) {
            mustA.add(ma.group(1));
        }
        Set<String> cannotB = new java.util.HashSet<>();
        Matcher mb = Pattern.compile("(?:不能|禁止|不可|不得)(.+?)(?:[，。；]|$)").matcher(reqB);
        while (mb.find()) {
            cannotB.add(mb.group(1));
        }
        for (String a : mustA) {
            for (String b : cannotB) {
                Set<Character> common = new java.util.HashSet<>();
                for (char c : a.toCharArray()) {
                    if (b.indexOf(c) >= 0) {
                        common.add(c);
                    }
                }
                if (common.size() >= 3) {
                    return true;
                }
            }
        }
        return false;
    }

}

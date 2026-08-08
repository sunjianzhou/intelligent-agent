package com.intelligent.agent.web.domain.skill;

import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Skill 领域服务（Plan 2 / Task 4）：
 * data/skills.json（{"skills": [...]}）持久化，形状与 Python Skill.to_dict 一致。
 */
@Slf4j
public class SkillService {

    private final JsonFileStore store;

    public SkillService(Path dataDir) {
        this.store = new JsonFileStore(dataDir);
    }

    public Map<String, Object> listSkills(String tag, boolean enabledOnly) {
        List<Map<String, Object>> skills = new ArrayList<>();
        for (Map<String, Object> skill : all()) {
            if (enabledOnly && !Boolean.TRUE.equals(skill.get("enabled"))) {
                continue;
            }
            if (tag != null && !tag.isBlank()) {
                @SuppressWarnings("unchecked")
                List<Object> tags = skill.get("scenario_tags") instanceof List
                        ? (List<Object>) skill.get("scenario_tags") : List.of();
                if (tags.stream().noneMatch(t -> tag.equals(String.valueOf(t)))) {
                    continue;
                }
            }
            skills.add(skill);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skills", skills);
        result.put("count", skills.size());
        return result;
    }

    public Map<String, Object> createSkill(Map<String, Object> body) {
        Map<String, Object> skill = new LinkedHashMap<>();
        String id = str(body.get("id"));
        skill.put("id", id == null || id.isBlank()
                ? "skill_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) : id);
        skill.put("name", str(body.get("name")));
        skill.put("description", str(body.getOrDefault("description", "")));
        skill.put("trigger_keywords", body.getOrDefault("trigger_keywords", List.of()));
        skill.put("tool_hints", body.getOrDefault("tool_hints", List.of()));
        skill.put("forced_tools", body.getOrDefault("forced_tools", List.of()));
        skill.put("scenario_tags", body.getOrDefault("scenario_tags", List.of()));
        skill.put("overall_strategy", str(body.getOrDefault("overall_strategy", "")));
        skill.put("steps", body.getOrDefault("steps", List.of()));
        skill.put("enabled", body.getOrDefault("enabled", true));
        String now = Instant.now().toString();
        skill.put("created_at", now);
        skill.put("updated_at", now);

        List<Map<String, Object>> skills = all();
        skills.add(skill);
        save(skills);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("skill", skill);
        return result;
    }

    public Map<String, Object> updateSkill(String skillId, Map<String, Object> body) {
        List<Map<String, Object>> skills = all();
        for (Map<String, Object> skill : skills) {
            if (skillId.equals(skill.get("id"))) {
                for (Map.Entry<String, Object> entry : body.entrySet()) {
                    if (!"id".equals(entry.getKey()) && entry.getValue() != null) {
                        skill.put(entry.getKey(), entry.getValue());
                    }
                }
                skill.put("updated_at", Instant.now().toString());
                save(skills);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("skill", skill);
                return result;
            }
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("success", false);
        error.put("message", "Skill 不存在");
        return error;
    }

    public Map<String, Object> deleteSkill(String skillId) {
        List<Map<String, Object>> skills = all();
        boolean removed = skills.removeIf(skill -> skillId.equals(skill.get("id")));
        if (removed) {
            save(skills);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", removed);
        result.put("message", removed ? "已删除" : "不存在");
        return result;
    }

    public Map<String, Object> toggleSkill(String skillId) {
        for (Map<String, Object> skill : all()) {
            if (skillId.equals(skill.get("id"))) {
                boolean enabled = !Boolean.TRUE.equals(skill.get("enabled"));
                skill.put("enabled", enabled);
                skill.put("updated_at", Instant.now().toString());
                save(all());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("enabled", enabled);
                return result;
            }
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("success", false);
        error.put("message", "不存在");
        return error;
    }

    public Map<String, Object> templates() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templates", BUILTIN_TEMPLATES);
        result.put("count", BUILTIN_TEMPLATES.size());
        return result;
    }

    public Map<String, Object> applyTemplate(String templateId) {
        Map<String, Object> template = null;
        for (Map<String, Object> t : BUILTIN_TEMPLATES) {
            if (templateId.equals(t.get("id"))) {
                template = t;
                break;
            }
        }
        if (template == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "模板 " + templateId + " 不存在");
            return error;
        }
        for (Map<String, Object> skill : all()) {
            if (template.get("name").equals(skill.get("name"))) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("success", false);
                error.put("message", "已存在同名 Skill：" + template.get("name") + "，请先删除再导入");
                return error;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>(template);
        data.remove("id");
        return createSkill(data);
    }

    // ── 内置模板（与 Python BUILTIN_TEMPLATES 前三个一致） ────

    private static final List<Map<String, Object>> BUILTIN_TEMPLATES = List.of(
            template("tpl_database", "数据库查询助手",
                    "查询 MySQL 数据库，自动分析表结构后执行 SQL",
                    List.of("查询数据库", "查表", "sql", "数据库"),
                    List.of("database", "sql"),
                    "用户需要查询数据库，必须先了解表结构再执行 SQL，严禁编造数据。"),
            template("tpl_github", "GitHub 代码助手",
                    "搜索仓库、查看代码、PR、Issue 等 GitHub 相关操作",
                    List.of("github", "仓库", "pr", "issue"),
                    List.of("github", "code"),
                    "通过 GitHub 工具获取真实数据，严禁编造任何仓库名称、链接或代码内容。"),
            template("tpl_file", "文件处理助手",
                    "读取、写入、分析本地文件，支持文本、CSV、代码文件",
                    List.of("读取文件", "写文件", "文件内容"),
                    List.of("file"),
                    "操作本地文件必须通过 FileTool，不能编造文件内容，路径必须来自用户提供或目录列表。")
    );

    private static Map<String, Object> template(String id, String name, String description,
                                                List<String> keywords, List<String> tags,
                                                String strategy) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("name", name);
        t.put("description", description);
        t.put("trigger_keywords", keywords);
        t.put("scenario_tags", tags);
        t.put("overall_strategy", strategy);
        t.put("steps", List.of());
        t.put("enabled", true);
        return t;
    }

    // ── 存储 ──────────────────────────────────────────────────

    private List<Map<String, Object>> all() {
        Map<String, Object> data = store.read("skills.json");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills = data == null ? new ArrayList<>()
                : (List<Map<String, Object>>) data.getOrDefault("skills", new ArrayList<>());
        return new ArrayList<>(skills);
    }

    private void save(List<Map<String, Object>> skills) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("skills", skills);
        store.write(new String[]{"skills.json"}, data);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

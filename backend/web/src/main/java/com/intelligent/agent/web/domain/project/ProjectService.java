package com.intelligent.agent.web.domain.project;

import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.memory.MemoryRepository;
import com.intelligent.agent.web.ai.memory.MemorySearchQuery;
import com.intelligent.agent.web.domain.InvalidRequestException;
import com.intelligent.agent.web.domain.NotFoundException;
import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 项目领域服务（Plan 2 / Task 3）。
 * <ul>
 *   <li>项目元数据 → data/projects/{user_id}/{project_id}.json；</li>
 *   <li>项目规格 / 上下文 nuggets → {@link MemoryRepository}（project_id 过滤）。</li>
 * </ul>
 * 任务分解为确定性实现（无 LLM），保证响应形状与 Python 一致。
 */
@Slf4j
public class ProjectService {

    private final JsonFileStore store;
    private final MemoryRepository memoryRepository;
    private final Map<String, AtomicInteger> contextVersions = new ConcurrentHashMap<>();

    public ProjectService(Path dataDir, MemoryRepository memoryRepository) {
        this.store = new JsonFileStore(dataDir);
        this.memoryRepository = memoryRepository;
    }

    // ── 项目 CRUD ─────────────────────────────────────────────

    public Map<String, Object> listProjects(String userId) {
        List<Map<String, Object>> projects = new ArrayList<>();
        for (Map<String, Object> project : store.listJson("projects", userId)) {
            if (project.get("id") != null) {
                projects.add(project);
            }
        }
        projects.sort((a, b) -> String.valueOf(b.get("updated_at"))
                .compareTo(String.valueOf(a.get("updated_at"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("projects", projects);
        result.put("count", projects.size());
        return result;
    }

    public Map<String, Object> createProject(String userId, Map<String, Object> body) {
        if (body == null) {
            throw new InvalidRequestException("请求体无效");
        }
        String title = body.get("title") == null ? "" : String.valueOf(body.get("title")).trim();
        if (title.isBlank()) {
            throw new InvalidRequestException("项目名称不能为空");
        }
        String now = Instant.now().toString();
        String projectId = str(body.get("id"));

        Map<String, Object> project = new LinkedHashMap<>();
        project.put("id", projectId == null || projectId.isBlank()
                ? "proj_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) : projectId);
        project.put("title", title);
        project.put("created_at", now);
        project.put("updated_at", now);
        project.put("session_ids", new ArrayList<>());
        project.put("context_version", 0);
        project.put("context_summary", "");
        project.put("spec", specMap("", 1, ""));
        project.put("task_tree", Map.of(
                "root_tasks", new ArrayList<>(), "auto_decompose", true, "last_updated", ""));
        project.put("synced", true);
        for (String key : List.of("session_ids", "context_version", "context_summary",
                "spec", "task_tree", "created_at")) {
            if (body.containsKey(key)) {
                project.put(key, body.get(key));
            }
        }
        store.write(new String[]{"projects", userId, project.get("id") + ".json"}, project);
        log.info("项目已创建: user={}, id={}, title={}", userId, project.get("id"), title);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("project", project);
        return result;
    }

    public Map<String, Object> getProject(String userId, String projectId) {
        Map<String, Object> project = load(userId, projectId);
        if (project == null) {
            throw new NotFoundException("项目不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("project", project);
        return result;
    }

    public Map<String, Object> updateProject(String userId, String projectId, Map<String, Object> body) {
        Map<String, Object> project = load(userId, projectId);
        if (project == null) {
            throw new NotFoundException("项目不存在");
        }
        if (body != null) {
            project.putAll(body);
        }
        project.put("id", projectId);
        project.put("updated_at", Instant.now().toString());
        project.put("synced", true);
        store.write(new String[]{"projects", userId, projectId + ".json"}, project);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("project", project);
        return result;
    }

    public Map<String, Object> deleteProject(String userId, String projectId) {
        if (!store.delete("projects", userId, projectId + ".json")) {
            throw new NotFoundException("项目不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("project_id", projectId);
        return result;
    }

    // ── 项目规格 / 上下文 / 任务 ───────────────────────────────

    public Map<String, Object> putSpec(String userId, String projectId, String content, int version) {
        // 与 Python 行为一致：写新规格前清掉该项目旧规格，只保留最新版本
        for (MemoryRecord old : memoryRepository.list(
                MemorySearchQuery.builder(userId, "", 100)
                        .projectId(projectId).type("project_spec").build())) {
            memoryRepository.delete(userId, old.id());
        }
        MemoryRecord spec = new MemoryRecord(
                "spec_" + projectId + "_v" + version, userId, content,
                null, projectId, "project_spec",
                Map.of("version", version), 0.8);
        memoryRepository.upsert(spec);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project_id", projectId);
        result.put("version", version);
        result.put("synced", true);
        return result;
    }

    public Map<String, Object> getSpec(String userId, String projectId) {
        List<MemoryRecord> specs = memoryRepository.list(
                MemorySearchQuery.builder(userId, "", 5)
                        .projectId(projectId).type("project_spec").build());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project_id", projectId);
        if (specs.isEmpty()) {
            result.put("content", "");
            result.put("version", 0);
            return result;
        }
        MemoryRecord latest = specs.get(0);
        Object version = latest.metadata().get("version");
        result.put("content", latest.content());
        result.put("version", version instanceof Number ? version : 1);
        return result;
    }

    public Map<String, Object> extractContext(String userId, String projectId) {
        int version = contextVersions.computeIfAbsent(
                userId + ":" + projectId, k -> new AtomicInteger()).incrementAndGet();
        int extracted = 0;
        Map<String, Object> project = load(userId, projectId);
        String nuggetSource = project == null
                ? "项目 " + projectId
                : "项目 " + project.getOrDefault("title", projectId);
        List<MemoryRecord> specs = memoryRepository.list(
                MemorySearchQuery.builder(userId, "", 1)
                        .projectId(projectId).type("project_spec").build());
        if (!specs.isEmpty()) {
            nuggetSource += "\n" + specs.get(0).content();
        }
        String nugget = "项目上下文: " + nuggetSource;
        memoryRepository.upsert(new MemoryRecord(
                "nugget_" + projectId + "_" + version, userId, nugget,
                null, projectId, "project_nugget", Map.of("version", version), 0.6));
        extracted = 1;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("extracted", extracted);
        result.put("version", version);
        return result;
    }

    public Map<String, Object> getContext(String userId, String projectId, String query, int limit) {
        List<MemoryRecord> nuggets = memoryRepository.list(
                MemorySearchQuery.builder(userId, query == null ? "" : query, Math.max(1, limit))
                        .projectId(projectId).type("project_nugget").build());
        List<String> contents = nuggets.stream().map(MemoryRecord::content).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project_id", projectId);
        result.put("nuggets", contents);
        return result;
    }

    public Map<String, Object> decomposeTasks(String userId, String projectId, String taskDescription) {
        String desc = taskDescription == null || taskDescription.isBlank()
                ? "请根据项目目标合理规划任务" : taskDescription;
        List<Map<String, Object>> rootTasks = new ArrayList<>();

        String[] lines = desc.split("[\n；;。]+");
        int index = 0;
        String rootTitle = lines[0] == null ? "" : lines[0].trim();
        if (rootTitle.isBlank()) {
            rootTitle = desc;
        }
        Map<String, Object> root = taskNode("task_" + UUID.randomUUID().toString().substring(0, 8),
                rootTitle.length() > 200 ? rootTitle.substring(0, 200) : rootTitle);
        List<Map<String, Object>> subtasks = new ArrayList<>();
        for (int i = 1; i < lines.length && subtasks.size() < 5; i++) {
            String line = lines[i].trim();
            if (!line.isBlank()) {
                subtasks.add(taskNode("task_" + UUID.randomUUID().toString().substring(0, 8),
                        line.length() > 200 ? line.substring(0, 200) : line));
            }
        }
        root.put("subtasks", subtasks);
        rootTasks.add(root);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project_id", projectId);
        result.put("task_tree", Map.of(
                "root_tasks", rootTasks, "auto_decompose", true));
        return result;
    }

    public Map<String, Object> getProjectTasks(String userId, String projectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project_id", projectId);
        result.put("task_tree", new ArrayList<>());
        result.put("note", "tasks are client-owned");
        return result;
    }

    // ── 内部辅助 ──────────────────────────────────────────────

    private Map<String, Object> load(String userId, String projectId) {
        return store.read("projects", userId, projectId + ".json");
    }

    private static Map<String, Object> specMap(String content, int version, String lastUpdated) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("content", content);
        spec.put("version", version);
        spec.put("last_updated", lastUpdated);
        spec.put("last_reviewed_turn", 0);
        return spec;
    }

    private static Map<String, Object> taskNode(String id, String title) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("title", title);
        node.put("status", "pending");
        node.put("subtasks", new ArrayList<>());
        node.put("notes", "");
        node.put("created_at", Instant.now().toString());
        node.put("completed_at", null);
        return node;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

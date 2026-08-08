package com.intelligent.agent.web.infrastructure.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;

/**
 * 逻辑数据导出清单（Plan 3 / Task 4）：
 * 记录导出批次、每个集合的导出文件、记录数与 SHA-256。
 * 与 Python 侧导出工具产出的 manifest.json 形状对齐。
 *
 * @param id          导出批次 ID
 * @param exportedAt  导出时间（ISO-8601）
 * @param collections 集合清单（name / record_count / sha256 / jsonl 文件名）
 */
public record LegacyExportManifest(
        String id,
        String exportedAt,
        List<CollectionEntry> collections) {

    public record CollectionEntry(
            String name,
            int recordCount,
            String sha256,
            String jsonl) {
    }

    public static LegacyExportManifest fromJson(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> root = mapper.readValue(json, new TypeReference<>() {});
        String id = String.valueOf(root.getOrDefault("id", "unknown"));
        String exportedAt = String.valueOf(root.getOrDefault("exported_at", ""));
        Object collectionsObj = root.get("collections");
        List<CollectionEntry> collections = mapper.convertValue(
                collectionsObj == null ? List.of() : collectionsObj,
                new TypeReference<List<CollectionEntry>>() {});
        return new LegacyExportManifest(id, exportedAt, collections);
    }
}

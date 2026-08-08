package com.intelligent.agent.web.infrastructure.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.memory.MemoryRepository;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逻辑数据导入器（Plan 3 / Task 4）：
 * <ul>
 *   <li>{@link #importJsonl(Path, MemoryRepository, String, String)}：逐行导入
 *       JSONL 逻辑记录并重新向量化（写入 Java MemoryRepository）；</li>
 *   <li>{@link #importBusinessJson(Path, Path)}：把角色/会话/项目等业务 JSON
 *       树拷贝到 Java 数据目录（不触碰 ChromaDB 内部布局）。</li>
 * </ul>
 * 导入前必须先通过 {@link MigrationValidator} 校验，dry-run 用副本卷。
 */
@Slf4j
public class LegacyDataImporter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 导入 JSONL 向量逻辑记录；每行 {id?, document, metadata, importance}。 */
    public int importJsonl(Path jsonl, MemoryRepository repository,
                           String userId, String collectionType) throws Exception {
        int count = 0;
        List<String> lines = Files.readAllLines(jsonl, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> record = objectMapper.readValue(
                    line, objectMapper.getTypeFactory()
                            .constructMapType(LinkedHashMap.class, String.class, Object.class));
            String id = String.valueOf(record.getOrDefault("id",
                    "mig_" + collectionType + "_" + count));
            String document = String.valueOf(record.getOrDefault("document", ""));
            if (document.isBlank()) {
                document = String.valueOf(record.getOrDefault("content", ""));
            }
            Object metadataObj = record.get("metadata");
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = metadataObj instanceof Map
                    ? (Map<String, Object>) metadataObj : new LinkedHashMap<>();
            metadata.put("source", "migration");
            metadata.put("collection", collectionType);
            double importance = record.get("importance") instanceof Number
                    ? ((Number) record.get("importance")).doubleValue() : 0.5;
            repository.upsert(new MemoryRecord(
                    id, userId, document, null, null, collectionType,
                    metadata, importance, Instant.now(), Instant.now(), 0));
            count++;
        }
        log.info("JSONL 导入完成: {} → {} 条 (collection={})", jsonl.getFileName(), count, collectionType);
        return count;
    }

    /** 拷贝业务 JSON 树（roles/conversations/projects/...）到目标数据目录。 */
    public int importBusinessJson(Path sourceDir, Path targetDataDir) throws Exception {
        if (!Files.isDirectory(sourceDir)) {
            return 0;
        }
        int copied = 0;
        try (var stream = Files.walk(sourceDir)) {
            for (Path source : stream.filter(Files::isRegularFile).toList()) {
                String name = source.getFileName().toString();
                if (!(name.endsWith(".json") || name.endsWith(".jsonl")
                        || name.endsWith(".md"))) {
                    continue;
                }
                Path relative = sourceDir.relativize(source);
                Path target = targetDataDir.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        log.info("业务 JSON 拷贝完成: {} → {} 个文件", sourceDir, copied);
        return copied;
    }
}

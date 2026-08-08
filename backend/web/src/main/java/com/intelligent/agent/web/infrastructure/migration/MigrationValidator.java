package com.intelligent.agent.web.infrastructure.migration;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 迁移校验器（Plan 3 / Task 4）：
 * <ul>
 *   <li>{@link #validate(LegacyExportManifest, Map)}：导入记录数与清单一致；</li>
 *   <li>{@link #validateHashes(Path, LegacyExportManifest)}：导出文件 SHA-256 与清单一致。</li>
 * </ul>
 * 任一项不一致即抛 {@link MigrationValidationException}，阻断导入。
 */
@Slf4j
public class MigrationValidator {

    public void validate(LegacyExportManifest manifest, Map<String, Integer> importedCounts) {
        if (manifest == null) {
            throw new MigrationValidationException("manifest 为空");
        }
        for (LegacyExportManifest.CollectionEntry entry : manifest.collections()) {
            Integer imported = importedCounts == null ? null : importedCounts.get(entry.name());
            if (imported == null) {
                throw new MigrationValidationException(
                        "集合未导入: " + entry.name() + "（清单要求 " + entry.recordCount() + " 条）");
            }
            if (imported != entry.recordCount()) {
                throw new MigrationValidationException(
                        "集合记录数不一致: " + entry.name() + " 清单=" + entry.recordCount()
                                + " 实际导入=" + imported);
            }
        }
        log.info("迁移校验通过：{} 个集合记录数一致", manifest.collections().size());
    }

    public void validateHashes(Path exportDir, LegacyExportManifest manifest) {
        for (LegacyExportManifest.CollectionEntry entry : manifest.collections()) {
            Path file = exportDir.resolve(entry.jsonl());
            if (!Files.exists(file)) {
                throw new MigrationValidationException("导出文件缺失: " + file);
            }
            String actual;
            try {
                actual = sha256(file);
            } catch (IOException e) {
                throw new MigrationValidationException(
                        "计算哈希失败: " + file + " (" + e.getMessage() + ")");
            }
            if (!entry.sha256().equalsIgnoreCase(actual)) {
                throw new MigrationValidationException(
                        "哈希不一致: " + entry.name() + " 清单=" + entry.sha256()
                                + " 实际=" + actual);
            }
        }
        log.info("迁移哈希校验通过：{} 个导出文件", manifest.collections().size());
    }

    public static String sha256(Path file) throws IOException {
        try {
            byte[] bytes = Files.readAllBytes(file);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

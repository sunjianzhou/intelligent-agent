package com.intelligent.agent.web.infrastructure.migration;

import com.intelligent.agent.web.ai.memory.MemoryRepository;
import com.intelligent.agent.web.infrastructure.vectorstore.VectorMemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 逻辑数据迁移校验测试（Plan 3 / Task 4）：
 * manifest 记录数 / SHA-256 与导入结果不一致时必须抛异常。
 */
class MigrationValidatorTest {

    @TempDir
    Path tempDir;

    private Path writeJsonl(String name, int lines) throws Exception {
        Path file = tempDir.resolve(name);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append("{\"id\":\"m").append(i).append("\",\"document\":\"记忆")
                    .append(i).append("\",\"metadata\":{},\"importance\":0.5}\n");
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static String sha256(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void validateRejectsRecordCountMismatch() throws Exception {
        Path jsonl = writeJsonl("ltm.jsonl", 2);
        LegacyExportManifest manifest = new LegacyExportManifest(
                "exp-1", "2026-08-08T00:00:00Z",
                java.util.List.of(new LegacyExportManifest.CollectionEntry(
                        "long_term", 3, sha256(jsonl), jsonl.getFileName().toString())));

        Map<String, Integer> imported = new LinkedHashMap<>();
        imported.put("long_term", 2);

        assertThatThrownBy(() -> new MigrationValidator().validate(manifest, imported))
                .isInstanceOf(MigrationValidationException.class);
    }

    @Test
    void validateRejectsMissingCollection() throws Exception {
        Path jsonl = writeJsonl("ltm.jsonl", 2);
        LegacyExportManifest manifest = new LegacyExportManifest(
                "exp-1", "2026-08-08T00:00:00Z",
                java.util.List.of(new LegacyExportManifest.CollectionEntry(
                        "long_term", 2, sha256(jsonl), jsonl.getFileName().toString())));

        assertThatThrownBy(() -> new MigrationValidator().validate(manifest, Map.of()))
                .isInstanceOf(MigrationValidationException.class);
    }

    @Test
    void validateAcceptsMatchingCounts() throws Exception {
        Path jsonl = writeJsonl("ltm.jsonl", 2);
        LegacyExportManifest manifest = new LegacyExportManifest(
                "exp-1", "2026-08-08T00:00:00Z",
                java.util.List.of(new LegacyExportManifest.CollectionEntry(
                        "long_term", 2, sha256(jsonl), jsonl.getFileName().toString())));

        Map<String, Integer> imported = new LinkedHashMap<>();
        imported.put("long_term", 2);

        new MigrationValidator().validate(manifest, imported);
    }

    @Test
    void validateRejectsHashMismatch() throws Exception {
        Path jsonl = writeJsonl("ltm.jsonl", 2);
        LegacyExportManifest manifest = new LegacyExportManifest(
                "exp-1", "2026-08-08T00:00:00Z",
                java.util.List.of(new LegacyExportManifest.CollectionEntry(
                        "long_term", 2, "0".repeat(64), jsonl.getFileName().toString())));

        assertThatThrownBy(() -> new MigrationValidator()
                .validateHashes(tempDir, manifest))
                .isInstanceOf(MigrationValidationException.class);
    }

    @Test
    void importerReembedsJsonlIntoMemoryRepository() throws Exception {
        Path jsonl = writeJsonl("ltm.jsonl", 3);
        MemoryRepository repository = new VectorMemoryRepository();

        int imported = new LegacyDataImporter()
                .importJsonl(jsonl, repository, "migrated-user", "long_term");

        assertThat(imported).isEqualTo(3);
        assertThat(repository.list(com.intelligent.agent.web.ai.memory.MemorySearchQuery
                .builder("migrated-user", "", 10).build())).hasSize(3);
    }
}

package com.intelligent.agent.web.infrastructure.migration;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实逻辑数据对账（Plan 3 / Task 6 确认项 1）：
 * 对 test resources 中迁移归档的 manifest + business 数据执行 SHA-256 校验与
 * 导入 dry-run（目标为临时目录，不污染 Java 数据目录），报告归档到 target/。
 */
class LegacyMigrationReconciliationTest {

    @Test
    void reconcilesLegacyExportAgainstDryRunTarget() throws Exception {
        // fixture 于 2026-08-23 随 Python 时代遗留清理迁入 test resources
        Path exportDir = Path.of(Objects.requireNonNull(
                getClass().getResource("/migration/export")).toURI());
        assertThat(Files.exists(exportDir.resolve("manifest.json")))
                .as("export manifest must exist").isTrue();

        String targetOverride = System.getenv("MIGRATION_TARGET");
        Path dryRunTarget = targetOverride != null && !targetOverride.isBlank()
                ? Path.of(targetOverride).toAbsolutePath()
                : Files.createTempDirectory("migration-dryrun");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int code = MigrationRunner.run(exportDir, dryRunTarget, new PrintStream(buffer, true, "UTF-8"));

        String report = buffer.toString(StandardCharsets.UTF_8);
        assertThat(code).isZero();
        assertThat(report).contains("对账通过：无记录丢失");
        // 归档报告到构建输出目录（幂等：不覆盖已有历史报告），不再写入仓库
        Path archiveDir = Path.of("target", "migration-reports").toAbsolutePath().normalize();
        Files.createDirectories(archiveDir);
        Path reportFile = archiveDir.resolve("reconciliation-" + System.currentTimeMillis() + ".txt");
        Files.writeString(reportFile, report, StandardCharsets.UTF_8);
        System.out.println("对账报告已归档: " + reportFile);
    }
}

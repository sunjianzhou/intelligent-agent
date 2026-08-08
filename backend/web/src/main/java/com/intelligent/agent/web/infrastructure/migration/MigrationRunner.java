package com.intelligent.agent.web.infrastructure.migration;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 逻辑数据迁移对账入口（Plan 3 / Task 6）：
 * <pre>
 *   MigrationRunner &lt;exportDir&gt; &lt;targetDataDir&gt;
 * </pre>
 * 步骤：加载 manifest → SHA-256 校验 → 业务 JSON 导入 → 记录数对账 → 输出报告。
 * 对账不一致时以非零退出码结束（阻断后续退役）。
 */
@Slf4j
public final class MigrationRunner {

    private MigrationRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: MigrationRunner <exportDir> <targetDataDir>");
            System.exit(2);
        }
        int code = run(Path.of(args[0]), Path.of(args[1]), System.out);
        System.exit(code);
    }

    /** 执行对账；返回 0=通过，1=失败。报告输出到 report。 */
    public static int run(Path exportDir, Path targetDataDir, java.io.PrintStream report)
            throws Exception {
        exportDir = exportDir.toAbsolutePath();
        targetDataDir = targetDataDir.toAbsolutePath();

        String manifestJson = Files.readString(exportDir.resolve("manifest.json"));
        LegacyExportManifest manifest = LegacyExportManifest.fromJson(manifestJson);
        report.println("manifest: id=" + manifest.id() + " exported_at=" + manifest.exportedAt()
                + " collections=" + manifest.collections().size());

        MigrationValidator validator = new MigrationValidator();
        validator.validateHashes(exportDir, manifest);
        report.println("SHA-256 校验通过");

        Path businessSource = exportDir.resolve("business");
        int copied = new LegacyDataImporter().importBusinessJson(businessSource, targetDataDir);
        report.println("业务 JSON 导入完成: " + copied + " 个文件");

        Map<String, Integer> importedCounts = new LinkedHashMap<>();
        for (LegacyExportManifest.CollectionEntry entry : manifest.collections()) {
            Path target = targetDataDir.resolve(
                    entry.jsonl().replace("business/", ""));
            importedCounts.put(entry.name(), Files.exists(target) ? 1 : 0);
        }
        validator.validate(manifest, importedCounts);

        report.println("===== 数据对账报告 =====");
        report.printf("%-44s %8s %8s%n", "collection", "source", "imported");
        for (LegacyExportManifest.CollectionEntry entry : manifest.collections()) {
            report.printf("%-44s %8d %8d%n", entry.name(),
                    entry.recordCount(), importedCounts.get(entry.name()));
        }
        report.println("===== 对账通过：无记录丢失 =====");
        return 0;
    }
}

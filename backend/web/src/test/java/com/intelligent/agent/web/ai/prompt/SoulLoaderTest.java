package com.intelligent.agent.web.ai.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SoulLoader 测试：真实 soul 目录加载、可选文件缺失、目录缺失降级。
 */
class SoulLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsRealSoulDirectory() {
        SoulLoader loader = new SoulLoader(Path.of("../../soul"));
        SoulData data = loader.data();

        assertThat(data.soul()).isNotBlank();
        assertThat(data.identity()).contains("霖君");
        assertThat(data.heartbeat()).isNotBlank();
        assertThat(data.rules()).contains("RULE-");
        assertThat(data.totalChars()).isGreaterThan(0);
        assertThat(data.fileSizes()).containsKeys("soul", "identity", "heart", "rules");
    }

    @Test
    void treatsMissingOptionalFilesAsEmpty() throws IOException {
        writeRequiredFiles(tempDir);
        SoulLoader loader = new SoulLoader(tempDir);
        SoulData data = loader.data();

        assertThat(data.heart()).isEmpty();
        assertThat(data.rules()).isEmpty();
        assertThat(data.whisper()).isEmpty();
        assertThat(data.soul()).isEqualTo("soul-content");
    }

    @Test
    void fallsBackToEmptyWhenDirectoryMissing() {
        SoulLoader loader = new SoulLoader(tempDir.resolve("nope"));
        SoulData data = loader.data();

        assertThat(data.soul()).isEmpty();
        assertThat(data.totalChars()).isZero();
    }

    @Test
    void reloadReflectsNewContent() throws IOException {
        writeRequiredFiles(tempDir);
        SoulLoader loader = new SoulLoader(tempDir);
        assertThat(loader.data().rules()).isEmpty();

        Files.writeString(tempDir.resolve("rules.md"), "### RULE-001: 测试", StandardCharsets.UTF_8);
        loader.reload();

        assertThat(loader.data().rules()).contains("RULE-001");
    }

    @Test
    void reportsFileSizes() throws IOException {
        writeRequiredFiles(tempDir);
        Files.writeString(tempDir.resolve("heart.md"), "心证内容", StandardCharsets.UTF_8);

        SoulData data = new SoulLoader(tempDir).data();
        assertThat(data.fileSizes().get("heart")).isEqualTo(4);
        assertThat(data.totalChars()).isGreaterThan(4);
    }

    private static void writeRequiredFiles(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SOUL.md"), "soul-content", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("USER.md"), "user-content", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("MEMORY.md"), "memory-content", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("IDENTITY.md"), "identity-content", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("HEARTBEAT.md"), "heartbeat-content", StandardCharsets.UTF_8);
    }
}

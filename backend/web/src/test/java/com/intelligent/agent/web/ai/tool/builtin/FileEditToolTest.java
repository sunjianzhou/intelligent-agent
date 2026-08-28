package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.ai.tool.builtin.file.FileEditTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-08：受控文件编辑工具测试——白名单内可写、越界/符号链接逃逸拒绝、
 * 写操作标记需要审批、追加/删除/复制/移动语义。
 */
class FileEditToolTest {

    @TempDir
    Path tempDir;

    private FileEditTool tool() {
        return new FileEditTool(List.of(tempDir));
    }

    @Test
    void definitionMarksMutationsAsApprovalRequired() {
        var definition = tool().definition();
        assertThat(definition.name()).isEqualTo("file_edit_tool");
        assertThat(definition.readOnly()).isFalse();
        assertThat(definition.approvalRequired()).isTrue();
    }

    @Test
    void writeCreatesFileAndReadsBack() throws Exception {
        Path file = tempDir.resolve("sub").resolve("note.txt");

        Object result = tool().execute(Map.of(
                "action", "write", "path", file.toString(), "content", "hello"));

        assertThat(String.valueOf(result)).contains("written=5");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    void writeIdempotentWhenContentUnchanged() throws Exception {
        Path file = tempDir.resolve("same.txt");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);

        Object result = tool().execute(Map.of(
                "action", "write", "path", file.toString(), "content", "abc"));

        assertThat(String.valueOf(result)).contains("changed=false");
    }

    @Test
    void appendAddsContentWithSeparator() throws Exception {
        Path file = tempDir.resolve("log.txt");
        Files.writeString(file, "first", StandardCharsets.UTF_8);

        tool().execute(Map.of("action", "append", "path", file.toString(), "content", "second"));

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("first\nsecond");
    }

    @Test
    void createDeleteCopyMoveWithinSafeDir() throws Exception {
        Path src = tempDir.resolve("src.txt");
        Path dst = tempDir.resolve("dst.txt");
        Path moved = tempDir.resolve("moved.txt");

        assertThat(String.valueOf(tool().execute(Map.of("action", "create", "path", src.toString()))))
                .contains("created=true");
        assertThat(Files.exists(src)).isTrue();

        assertThat(String.valueOf(tool().execute(Map.of(
                "action", "copy", "path", src.toString(), "destination", dst.toString()))))
                .contains("to=" + dst);
        assertThat(Files.exists(dst)).isTrue();

        assertThat(String.valueOf(tool().execute(Map.of(
                "action", "move", "path", src.toString(), "destination", moved.toString()))))
                .contains("to=" + moved);
        assertThat(Files.exists(src)).isFalse();
        assertThat(Files.exists(moved)).isTrue();

        assertThat(String.valueOf(tool().execute(Map.of("action", "delete", "path", moved.toString()))))
                .contains("deleted=true");
        assertThat(Files.exists(moved)).isFalse();
    }

    @Test
    void rejectsPathOutsideSafeDirIncludingDotDotEscape() {
        FileEditTool tool = tool();

        Object direct = tool.execute(Map.of(
                "action", "write", "path", tempDir.resolve("..").resolve("secret.txt").toString(),
                "content", "x"));
        assertThat(String.valueOf(direct)).contains("安全目录");

        Object destinationEscape = tool.execute(Map.of(
                "action", "copy",
                "path", tempDir.resolve("a.txt").toString(),
                "destination", tempDir.resolve("..").resolve("b.txt").toString()));
        assertThat(String.valueOf(destinationEscape)).contains("安全目录");
    }

    @Test
    void rejectsSymlinkEscape() throws Exception {
        Path outside = tempDir.resolve("..").resolve("outside-" + System.nanoTime() + ".txt");
        try {
            Files.writeString(outside, "secret", StandardCharsets.UTF_8);
            Path link = tempDir.resolve("link.txt");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | java.io.IOException e) {
                // 平台不支持符号链接时跳过
                return;
            }

            Object result = tool().execute(Map.of(
                    "action", "write", "path", link.toString(), "content", "hacked"));

            assertThat(String.valueOf(result)).contains("安全目录");
            assertThat(Files.readString(outside, StandardCharsets.UTF_8)).isEqualTo("secret");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void deleteRejectsDirectories() throws Exception {
        Path dir = tempDir.resolve("adir");
        Files.createDirectory(dir);

        Object result = tool().execute(Map.of("action", "delete", "path", dir.toString()));

        assertThat(String.valueOf(result)).contains("仅支持文件");
        assertThat(Files.exists(dir)).isTrue();
    }
}

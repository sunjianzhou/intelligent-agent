package com.intelligent.agent.client;

import com.intelligent.agent.client.auth.TokenStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI 命令发现与 token 安全存储测试（Plan 3 / Task 1）。
 */
class MainTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesChatCommand() {
        assertThat(new CommandLine(new Main()).getSubcommands()).containsKey("chat");
    }

    @Test
    void exposesLoginCommand() {
        assertThat(new CommandLine(new Main()).getSubcommands()).containsKey("login");
    }

    @Test
    void tokenStorePersistsAndRestrictsPermissions() throws Exception {
        TokenStore store = new TokenStore(tempDir.resolve("token"));

        store.save("eyJhbGciOiJIUzI1NiJ9.scoped-cli-token");
        assertThat(store.load()).contains("eyJhbGciOiJIUzI1NiJ9.scoped-cli-token");

        Path file = tempDir.resolve("token");
        assertThat(Files.exists(file)).isTrue();
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(file))
                    .doesNotContain(java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                            java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
        }
    }
}

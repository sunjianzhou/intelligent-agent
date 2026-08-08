package com.intelligent.agent.client.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * 本地 token 存储（Plan 3 / Task 1）。
 * <p>
 * 只保存 scoped CLI token，绝不保存 JWT_SECRET。文件权限收紧为仅属主可读写
 * （POSIX 下设置 rw-------；Windows 下依赖用户目录 ACL，文件位于
 * ~/.intelligent-agent/ 下）。
 */
public class TokenStore {

    private final Path file;

    public TokenStore(Path file) {
        this.file = file;
    }

    public static TokenStore defaultStore() {
        String home = System.getProperty("user.home", ".");
        return new TokenStore(Path.of(home, ".intelligent-agent", "token"));
    }

    public void save(String token) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, token, StandardCharsets.UTF_8);
        restrictPermissions();
    }

    public String load() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    public void delete() throws IOException {
        Files.deleteIfExists(file);
    }

    public Path path() {
        return file;
    }

    private void restrictPermissions() {
        try {
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(file, perms);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows 无 POSIX 视图：依赖用户目录 ACL，忽略
        }
    }
}

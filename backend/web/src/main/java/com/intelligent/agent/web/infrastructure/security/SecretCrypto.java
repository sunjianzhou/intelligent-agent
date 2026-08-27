package com.intelligent.agent.web.infrastructure.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 敏感字段落盘加密（AES-128-GCM），R-11 起与 JWT 解耦。
 * <p>
 * 两种模式：
 * <ul>
 *   <li>密钥文件模式 {@link #SecretCrypto(String, Path)}：独立密钥文件 {@code data/keys/key.<id>.key}
 *       （32 字节随机，hex），密文带版本头 {@code enc:<keyId>:<base64(iv+ct)>}；旧密钥文件保留，
 *       轮换后旧密文仍可按版本头解密，新写入用新密钥。</li>
 *   <li>字符串模式 {@link #SecretCrypto(String)}（测试/旧装配兼容）：密钥由 JWT 派生，密文旧格式
 *       {@code enc:<base64(iv+ct)>}，无版本头。</li>
 * </ul>
 * 解密兼容历史明文与旧格式密文（JWT 派生密钥），解密失败时原样返回避免功能中断。
 */
public final class SecretCrypto {

    private static final String PREFIX = "enc:";
    private static final String KEY_FILE_PREFIX = "key.";
    private static final String KEY_FILE_SUFFIX = ".key";
    private static final int IV_LENGTH = 12;

    private boolean enabled;
    /** JWT 派生密钥：字符串模式主键 + 密钥文件模式的旧格式密文兼容。 */
    private final SecretKeySpec legacyKey;
    /** 密钥文件 ring：keyId → AES key（含历史版本，支持平滑轮换）。 */
    private final Map<String, SecretKeySpec> ring = new ConcurrentHashMap<>();
    private final Path keyDir;
    private volatile String currentKeyId;
    private final SecureRandom random = new SecureRandom();

    /** 字符串模式（JWT 派生，兼容旧行为；不读写密钥文件）。 */
    public SecretCrypto(String secret) {
        this(secret, null);
    }

    /** 密钥文件模式：独立密钥文件 + keyId 版本化密文；jwtSecret 仅用于旧格式密文兼容。 */
    public SecretCrypto(String jwtSecret, Path keyDir) {
        this.legacyKey = deriveKey(jwtSecret);
        this.keyDir = keyDir;
        if (keyDir == null) {
            this.enabled = jwtSecret != null && !jwtSecret.isBlank();
        } else {
            this.enabled = true;
            loadOrCreateKeyRing();
        }
    }

    public static SecretCrypto disabled() {
        return new SecretCrypto("");
    }

    public boolean enabled() {
        return enabled;
    }

    /** 当前写入密钥 id（密钥文件模式；字符串模式返回 null）。 */
    public String currentKeyId() {
        return currentKeyId;
    }

    /**
     * 平滑轮换：生成新密钥文件并切换写入密钥；旧密文按版本头继续用旧密钥解密。
     *
     * @return 新密钥 id；字符串模式返回 null（不支持轮换）
     */
    public String rotate() {
        if (keyDir == null) {
            return null;
        }
        synchronized (this) {
            int next = 1;
            try {
                next = Integer.parseInt(currentKeyId) + 1;
            } catch (NumberFormatException ignored) {
                // 非数字 id 时退回时间戳
            }
            String newId = String.valueOf(next);
            SecretKeySpec key = generateKey();
            writeKeyFile(newId, key);
            ring.put(newId, key);
            currentKeyId = newId;
            return newId;
        }
    }

    public String encrypt(String plaintext) {
        if (!enabled || plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            SecretKeySpec key = currentKeyId != null ? ring.get(currentKeyId) : legacyKey;
            if (key == null) {
                return plaintext;
            }
            byte[] out = gcm(key, iv, plaintext.getBytes(StandardCharsets.UTF_8));
            String payload = Base64.getEncoder().encodeToString(out);
            return currentKeyId != null ? PREFIX + currentKeyId + ":" + payload
                    : PREFIX + payload;
        } catch (Exception e) {
            throw new IllegalStateException("敏感字段加密失败", e);
        }
    }

    /** 解密；未启用、历史明文或解密失败时原样返回，避免功能中断。 */
    public String decrypt(String stored) {
        if (stored == null || !enabled || !stored.startsWith(PREFIX)) {
            return stored;
        }
        String body = stored.substring(PREFIX.length());
        try {
            int colon = body.indexOf(':');
            if (colon < 0) {
                // 旧格式 enc:<b64(iv+ct)>：JWT 派生密钥
                if (legacyKey == null) {
                    return stored;
                }
                return decryptBytes(legacyKey, Base64.getDecoder().decode(body));
            }
            String keyId = body.substring(0, colon);
            SecretKeySpec key = ring.get(keyId);
            if (key == null) {
                return stored;
            }
            return decryptBytes(key, Base64.getDecoder().decode(body.substring(colon + 1)));
        } catch (Exception e) {
            return stored;
        }
    }

    // ── 内部 ────────────────────────────────────────────────

    private void loadOrCreateKeyRing() {
        try {
            Files.createDirectories(keyDir);
            try (Stream<Path> files = Files.list(keyDir)) {
                files.filter(p -> p.getFileName().toString().startsWith(KEY_FILE_PREFIX)
                                && p.getFileName().toString().endsWith(KEY_FILE_SUFFIX))
                        .forEach(p -> {
                            try {
                                String name = p.getFileName().toString();
                                String id = name.substring(KEY_FILE_PREFIX.length(),
                                        name.length() - KEY_FILE_SUFFIX.length());
                                byte[] bytes = hexDecode(
                                        Files.readString(p, StandardCharsets.UTF_8).trim());
                                ring.put(id, new SecretKeySpec(bytes, "AES"));
                            } catch (Exception e) {
                                throw new IllegalStateException("密钥文件加载失败: " + p, e);
                            }
                        });
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化密钥目录: " + keyDir, e);
        }
        if (ring.isEmpty()) {
            SecretKeySpec key = generateKey();
            ring.put("1", key);
            currentKeyId = "1";
            writeKeyFile("1", key);
        } else {
            currentKeyId = ring.keySet().stream()
                    .filter(id -> id.matches("\\d+"))
                    .max(Comparator.comparingLong(Long::parseLong))
                    .orElseGet(() -> ring.keySet().stream().findFirst().orElse("1"));
        }
    }

    private void writeKeyFile(String id, SecretKeySpec key) {
        try {
            Path file = keyDir.resolve(KEY_FILE_PREFIX + id + KEY_FILE_SUFFIX);
            Files.writeString(file, hexEncode(key.getEncoded()), StandardCharsets.UTF_8);
            restrictPermissions(file);
        } catch (IOException e) {
            throw new IllegalStateException("密钥文件写入失败: " + id, e);
        }
    }

    /** 尽力收紧权限（POSIX 平台 0600；Windows 跳过）。 */
    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions
                    .fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // 非 POSIX 文件系统（Windows）忽略
        }
    }

    private static SecretKeySpec generateKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new SecretKeySpec(bytes, "AES");
    }

    private static SecretKeySpec deriveKey(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(Arrays.copyOf(digest, 16), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化加密密钥", e);
        }
    }

    private static byte[] gcm(SecretKeySpec key, byte[] iv, byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plaintext);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    private static String decryptBytes(SecretKeySpec key, byte[] raw) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, raw, 0, IV_LENGTH));
        return new String(cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH),
                StandardCharsets.UTF_8);
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] hexDecode(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}

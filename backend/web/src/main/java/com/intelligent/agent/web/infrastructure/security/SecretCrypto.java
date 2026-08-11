package com.intelligent.agent.web.infrastructure.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 敏感字段落盘加密（AES-128-GCM）。
 * <p>
 * 密钥由 {@code auth.jwt.secret}（JWT_SECRET）SHA-256 派生，无需额外环境变量。
 * 密文以 {@code enc:} 前缀标记；未启用密钥或读取到历史明文时原样返回，
 * 保证存量数据与降级场景可用。
 */
public final class SecretCrypto {

    private static final String PREFIX = "enc:";

    private final SecretKeySpec key;
    private final boolean enabled;
    private final SecureRandom random = new SecureRandom();

    public SecretCrypto(String secret) {
        this.enabled = secret != null && !secret.isBlank();
        if (enabled) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(secret.getBytes(StandardCharsets.UTF_8));
                this.key = new SecretKeySpec(Arrays.copyOf(digest, 16), "AES");
            } catch (Exception e) {
                throw new IllegalStateException("无法初始化加密密钥", e);
            }
        } else {
            this.key = null;
        }
    }

    public static SecretCrypto disabled() {
        return new SecretCrypto("");
    }

    public boolean enabled() {
        return enabled;
    }

    public String encrypt(String plaintext) {
        if (!enabled || plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("敏感字段加密失败", e);
        }
    }

    /** 解密；未启用、历史明文或解密失败时原样返回，避免功能中断。 */
    public String decrypt(String stored) {
        if (stored == null || !enabled || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, raw, 0, 12));
            return new String(cipher.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return stored;
        }
    }
}

package com.intelligent.agent.web.wecom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 企业微信消息加解密：
 * - 签名：SHA1(sort([token, timestamp, nonce, encrypt]))
 * - AES key：Base64Decode(EncodingAESKey + "=")，32 字节
 * - IV：AES key 前 16 字节
 * - 明文格式：random16 + bigEndian4(len) + content + corpId
 * - 填充：PKCS#7，block size = 32
 */
public final class WeComCrypto {

    private WeComCrypto() {}

    // ── 签名验证 ──────────────────────────────────────────────────

    public static boolean verifySignature(String token, String timestamp,
                                          String nonce, String encrypt,
                                          String expected) {
        String[] parts = {token, timestamp, nonce, encrypt};
        Arrays.sort(parts);
        String combined = String.join("", parts);
        return sha1Hex(combined).equalsIgnoreCase(expected);
    }

    // ── 解密 ──────────────────────────────────────────────────────

    public static String decrypt(String encryptedB64, String encodingAesKey) throws Exception {
        byte[] key       = Base64.getDecoder().decode(encodingAesKey + "=");
        byte[] iv        = Arrays.copyOfRange(key, 0, 16);
        byte[] cipherRaw = Base64.getDecoder().decode(encryptedB64);

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv));
        byte[] decrypted = cipher.doFinal(cipherRaw);

        // 移除 PKCS#7 填充（block 32）
        int padLen = decrypted[decrypted.length - 1] & 0xFF;
        if (padLen < 1 || padLen > 32) padLen = 0;
        byte[] unpadded = Arrays.copyOfRange(decrypted, 0, decrypted.length - padLen);

        // 跳过 16 字节随机前缀；读 4 字节大端 int = 内容长度
        int msgLen = ByteBuffer.wrap(unpadded, 16, 4).getInt();
        return new String(unpadded, 20, msgLen, StandardCharsets.UTF_8);
    }

    // ── 加密（被动回复用，暂留备用）──────────────────────────────

    public static String encrypt(String plaintext, String encodingAesKey,
                                  String corpId) throws Exception {
        byte[] key        = Base64.getDecoder().decode(encodingAesKey + "=");
        byte[] iv         = Arrays.copyOfRange(key, 0, 16);
        byte[] random     = new byte[16];
        new SecureRandom().nextBytes(random);

        byte[] msgBytes    = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] corpBytes   = corpId.getBytes(StandardCharsets.UTF_8);

        // 拼装：random16 + bigEndian4(len) + msg + corpId
        ByteBuffer buf = ByteBuffer.allocate(16 + 4 + msgBytes.length + corpBytes.length);
        buf.put(random);
        buf.putInt(msgBytes.length);
        buf.put(msgBytes);
        buf.put(corpBytes);
        byte[] content = buf.array();

        // PKCS#7 填充 block=32
        int padLen = 32 - (content.length % 32);
        byte[] padded = new byte[content.length + padLen];
        System.arraycopy(content, 0, padded, 0, content.length);
        Arrays.fill(padded, content.length, padded.length, (byte) padLen);

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv));
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
    }

    // ── 工具 ──────────────────────────────────────────────────────

    static String sha1Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-1 unavailable", e);
        }
    }
}

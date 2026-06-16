package com.intelligent.agent.web.feishu;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class FeishuCrypto {

    private FeishuCrypto() {}

    public static String decrypt(String cipherB64, String encryptKey) throws Exception {
        byte[] key       = sha256Bytes(encryptKey);
        byte[] cipherRaw = Base64.getDecoder().decode(cipherB64);
        byte[] iv        = Arrays.copyOfRange(cipherRaw, 0, 16);
        byte[] encrypted = Arrays.copyOfRange(cipherRaw, 16, cipherRaw.length);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    public static String encrypt(String plaintext, String encryptKey) throws Exception {
        byte[] key = sha256Bytes(encryptKey);
        byte[] iv  = new byte[16];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[16 + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, 16);
        System.arraycopy(encrypted, 0, combined, 16, encrypted.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    public static boolean verifyUrlSignature(String timestamp, String nonce,
                                              String encryptKey, String expected) {
        String computed = sha256Hex(timestamp + nonce + encryptKey);
        return computed.equalsIgnoreCase(expected);
    }

    public static boolean verifyEventSignature(String timestamp, String nonce,
                                                String verificationToken, String encryptKey,
                                                String expected) {
        String computed = sha256Hex(timestamp + nonce + verificationToken + encryptKey);
        return computed.equalsIgnoreCase(expected);
    }

    private static byte[] sha256Bytes(String input) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}

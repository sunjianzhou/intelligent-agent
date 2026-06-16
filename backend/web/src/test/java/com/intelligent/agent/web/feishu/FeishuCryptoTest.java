package com.intelligent.agent.web.feishu;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FeishuCryptoTest {

    private static final String KEY = "hello-feishu-encrypt-key-32chars";

    // ── decrypt ──────────────────────────────────────────────────────
    @Test
    void encrypt_then_decrypt_roundtrip() throws Exception {
        String plain = "{\"type\":\"im.message.receive_v1\",\"data\":\"test\"}";
        String cipher = FeishuCrypto.encrypt(plain, KEY);
        assertThat(FeishuCrypto.decrypt(cipher, KEY)).isEqualTo(plain);
    }

    @Test
    void decrypt_wrongKey_throwsException() {
        assertThatThrownBy(() -> FeishuCrypto.decrypt("aGVsbG8=", "wrong-key-123"))
                .isInstanceOf(Exception.class);
    }

    // ── verifyUrlSignature ────────────────────────────────────────────
    // sha256(timestamp + nonce + encryptKey)
    @Test
    void verifyUrlSignature_match() throws Exception {
        String ts = "1718500000";
        String nonce = "abc123";
        String expected = sha256Hex(ts + nonce + KEY);
        assertThat(FeishuCrypto.verifyUrlSignature(ts, nonce, KEY, expected)).isTrue();
    }

    @Test
    void verifyUrlSignature_mismatch() {
        assertThat(FeishuCrypto.verifyUrlSignature("ts", "n", KEY, "wrong")).isFalse();
    }

    // ── verifyEventSignature ──────────────────────────────────────────
    // sha256(timestamp + nonce + token + encryptKey)
    @Test
    void verifyEventSignature_match() throws Exception {
        String ts = "1718500000";
        String nonce = "xyz789";
        String token = "verify-token-abc";
        String expected = sha256Hex(ts + nonce + token + KEY);
        assertThat(FeishuCrypto.verifyEventSignature(ts, nonce, token, KEY, expected)).isTrue();
    }

    @Test
    void verifyEventSignature_mismatch() {
        assertThat(FeishuCrypto.verifyEventSignature("ts", "n", "tok", KEY, "bad")).isFalse();
    }

    @Test
    void urlSignature_and_eventSignature_differ() throws Exception {
        String ts = "1718500000", nonce = "n", tok = "t";
        String urlSig   = sha256Hex(ts + nonce + KEY);
        String eventSig = sha256Hex(ts + nonce + tok + KEY);
        assertThat(urlSig).isNotEqualTo(eventSig);
    }

    private static String sha256Hex(String input) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

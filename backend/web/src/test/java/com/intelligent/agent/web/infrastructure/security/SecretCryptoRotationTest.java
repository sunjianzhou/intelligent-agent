package com.intelligent.agent.web.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-11：独立密钥文件 + keyId 版本化密文 + 平滑轮换 + 旧格式兼容。
 */
class SecretCryptoRotationTest {

    @TempDir
    Path tempDir;

    @Test
    void keyFileModeRoundTripsAndPersistsAcrossInstances() throws Exception {
        SecretCrypto first = new SecretCrypto("jwt-a", tempDir);
        String stored = first.encrypt("sk-secret");

        assertThat(stored).startsWith("enc:1:");
        assertThat(first.decrypt(stored)).isEqualTo("sk-secret");
        assertThat(Files.exists(tempDir.resolve("key.1.key"))).isTrue();
        assertThat(Files.readString(tempDir.resolve("key.1.key")).trim()).hasSize(64);

        // 新实例（甚至不同 JWT）从密钥文件恢复，可解旧密文——密钥与 JWT 解耦
        SecretCrypto second = new SecretCrypto("jwt-b", tempDir);
        assertThat(second.currentKeyId()).isEqualTo("1");
        assertThat(second.decrypt(stored)).isEqualTo("sk-secret");
    }

    @Test
    void rotateKeepsOldCiphertextReadableAndNewWritesUseNewKey() {
        SecretCrypto crypto = new SecretCrypto("jwt", tempDir);
        String oldCipher = crypto.encrypt("old-value");
        assertThat(oldCipher).startsWith("enc:1:");

        String newKeyId = crypto.rotate();
        String newCipher = crypto.encrypt("new-value");

        assertThat(newKeyId).isEqualTo("2");
        assertThat(newCipher).startsWith("enc:2:");
        assertThat(crypto.decrypt(oldCipher)).isEqualTo("old-value");
        assertThat(crypto.decrypt(newCipher)).isEqualTo("new-value");
        assertThat(Files.exists(tempDir.resolve("key.1.key"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("key.2.key"))).isTrue();

        // 重启后两个版本密文都可读
        SecretCrypto reloaded = new SecretCrypto("", tempDir);
        assertThat(reloaded.currentKeyId()).isEqualTo("2");
        assertThat(reloaded.decrypt(oldCipher)).isEqualTo("old-value");
        assertThat(reloaded.decrypt(newCipher)).isEqualTo("new-value");
    }

    @Test
    void legacyJwtCiphertextStillDecryptsInKeyFileMode() {
        SecretCrypto legacy = new SecretCrypto("jwt-secret-abc");
        String oldFormat = legacy.encrypt("sk-legacy-encrypted");
        assertThat(oldFormat).startsWith("enc:");
        assertThat(oldFormat.substring("enc:".length())).doesNotContain(":");
        assertThat(oldFormat.split(":")).hasSize(2); // enc:base64，无版本头

        SecretCrypto migrated = new SecretCrypto("jwt-secret-abc", tempDir);
        assertThat(migrated.decrypt(oldFormat)).isEqualTo("sk-legacy-encrypted");
    }

    @Test
    void stringModeStillProducesLegacyFormat() {
        SecretCrypto crypto = new SecretCrypto("jwt-secret-abc");
        String stored = crypto.encrypt("sk-1");
        assertThat(stored).startsWith("enc:");
        assertThat(stored.substring("enc:".length())).doesNotContain(":");
        assertThat(crypto.currentKeyId()).isNull();
        assertThat(crypto.rotate()).isNull();
        assertThat(crypto.decrypt(stored)).isEqualTo("sk-1");
    }
}

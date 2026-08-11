package com.intelligent.agent.web.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretCryptoTest {

    @Test
    void encryptDecryptRoundTrip() {
        SecretCrypto crypto = new SecretCrypto("jwt-secret-abc");

        String stored = crypto.encrypt("sk-super-secret");

        assertThat(stored).startsWith("enc:");
        assertThat(stored).doesNotContain("sk-super-secret");
        assertThat(crypto.decrypt(stored)).isEqualTo("sk-super-secret");
    }

    @Test
    void legacyPlaintextPassesThrough() {
        SecretCrypto crypto = new SecretCrypto("jwt-secret-abc");

        assertThat(crypto.decrypt("sk-legacy-plain")).isEqualTo("sk-legacy-plain");
    }

    @Test
    void disabledCryptoIsIdentity() {
        SecretCrypto crypto = SecretCrypto.disabled();

        assertThat(crypto.encrypt("sk-1")).isEqualTo("sk-1");
        assertThat(crypto.decrypt("sk-1")).isEqualTo("sk-1");
    }
}

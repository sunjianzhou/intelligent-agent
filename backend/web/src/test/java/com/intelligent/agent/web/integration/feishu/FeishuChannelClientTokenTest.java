package com.intelligent.agent.web.integration.feishu;

import com.intelligent.agent.web.feishu.FeishuMessageSender;
import com.intelligent.agent.web.im.RetryConfig;
import com.intelligent.agent.web.infrastructure.security.SecretCrypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 飞书 OAuth token 落盘加密（Fix：与云端 API Key 同一 SecretCrypto）。
 */
class FeishuChannelClientTokenTest {

    @TempDir
    Path dataDir;

    @Test
    void tokensAreEncryptedAtRestAndDecryptedOnRead() throws Exception {
        FeishuChannelClient client = new FeishuChannelClient(
                mock(FeishuMessageSender.class), dataDir, true,
                RetryConfig.DEFAULT, new SecretCrypto("test-secret-0123456789"));

        client.saveUserToken("ou_test", "at-plain", "rt-plain", 12345L);

        String raw = Files.readString(dataDir.resolve("feishu_tokens.json"));
        assertThat(raw).contains("enc:");
        assertThat(raw).doesNotContain("at-plain").doesNotContain("rt-plain");

        Map<String, Object> token = client.getUserToken("ou_test");
        assertThat(token.get("access_token")).isEqualTo("at-plain");
        assertThat(token.get("refresh_token")).isEqualTo("rt-plain");
        assertThat(((Number) token.get("refresh_expires_at")).longValue()).isEqualTo(12345L);
    }

    @Test
    void legacyPlaintextTokensStillReadable() throws Exception {
        FeishuChannelClient client = new FeishuChannelClient(
                mock(FeishuMessageSender.class), dataDir, true,
                RetryConfig.DEFAULT, new SecretCrypto("test-secret-0123456789"));
        Files.writeString(dataDir.resolve("feishu_tokens.json"),
                "{\"ou_legacy\":{\"access_token\":\"at-legacy\",\"refresh_token\":\"rt-legacy\",\"refresh_expires_at\":1}}");

        Map<String, Object> token = client.getUserToken("ou_legacy");

        assertThat(token.get("access_token")).isEqualTo("at-legacy");
        assertThat(token.get("refresh_token")).isEqualTo("rt-legacy");
    }
}

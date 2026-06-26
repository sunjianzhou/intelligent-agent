package com.intelligent.agent.web.feishu;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

class FeishuWebSocketClientTest {

    private FeishuWebSocketClient buildClient(boolean enabled, String appId, String appSecret) {
        FeishuConfig config = new FeishuConfig();
        config.setEnabled(enabled);
        config.setAppId(appId);
        config.setAppSecret(appSecret);
        config.setEncryptKey("test-key");
        FeishuEventController controller = Mockito.mock(FeishuEventController.class);
        return new FeishuWebSocketClient(config, controller, Executors.newSingleThreadExecutor());
    }

    @Test
    void isAutoStartup_false_whenDisabled() {
        FeishuWebSocketClient client = buildClient(false, "id", "secret");
        assertThat(client.isAutoStartup()).isFalse();
    }

    @Test
    void isAutoStartup_true_whenEnabled() {
        FeishuWebSocketClient client = buildClient(true, "id", "secret");
        assertThat(client.isAutoStartup()).isTrue();
    }

    @Test
    void start_throwsIllegalState_whenAppIdBlank() {
        FeishuWebSocketClient client = buildClient(true, "", "secret");
        assertThatThrownBy(client::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("appId");
    }

    @Test
    void start_throwsIllegalState_whenAppSecretBlank() {
        FeishuWebSocketClient client = buildClient(true, "id", "");
        assertThatThrownBy(client::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("appSecret");
    }

    @Test
    void start_noop_whenDisabled() {
        FeishuWebSocketClient client = buildClient(false, "", "");
        assertThatCode(client::start).doesNotThrowAnyException();
        assertThat(client.isRunning()).isFalse();
    }
}

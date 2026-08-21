package com.intelligent.agent.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.wecom.WeComConfig;
import com.intelligent.agent.web.wecom.WeComMessageSender;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * IM 真实送达验证（B 验收遗留项）：
 * <p>
 * 手动运行 {@code mvn test -Dgroups=im-verify -DexcludedGroups=}（默认 surefire
 * excludedGroups=eval,im-verify 排除，绝不进 CI 门）。需要真实凭证环境变量
 * （.env.docker 中的 FEISHU_APP_ID/FEISHU_APP_SECRET/FEISHU_HEARTBEAT_RECEIVER_ID
 * 与 WECOM_CORP_ID/WECOM_SECRET）；凭证缺失时整类跳过。
 * <p>
 * 副作用提示：feishu 用例会向 FEISHU_HEARTBEAT_RECEIVER_ID 真实发送一条
 * 带时间戳标记的文本消息，用于在飞书端人工确认送达。
 */
@Tag("im-verify")
class ImDeliveryVerifyTest {

    private static final String MARKER = "【智能体送达验证 " + System.currentTimeMillis() + "】";

    @Test
    void feishuTextReachesHeartbeatReceiver() {
        String appId = env("FEISHU_APP_ID");
        String appSecret = env("FEISHU_APP_SECRET");
        String receiver = env("FEISHU_HEARTBEAT_RECEIVER_ID");
        assumeTrue(!appId.isEmpty() && !appSecret.isEmpty() && !receiver.isEmpty(),
                "需要 FEISHU_APP_ID / FEISHU_APP_SECRET / FEISHU_HEARTBEAT_RECEIVER_ID");

        FeishuConfig config = new FeishuConfig();
        config.setEnabled(true);
        config.setAppId(appId);
        config.setAppSecret(appSecret);
        FeishuMessageSender sender = new FeishuMessageSender(
                config, new RestTemplate(), new ObjectMapper(), "https://open.feishu.cn");

        String messageId = sender.sendTextByOpenId(receiver,
                MARKER + " 飞书文本消息真实送达验证，请查收。");

        assertThat(messageId)
                .as("飞书 sendTextByOpenId 应返回 message_id（真实送达成功）")
                .isNotBlank();
    }

    @Test
    void wecomAccessTokenIsFetchableWithConfiguredCredentials() {
        String corpId = env("WECOM_CORP_ID");
        String secret = env("WECOM_SECRET");
        assumeTrue(!corpId.isEmpty() && !secret.isEmpty(),
                "需要 WECOM_CORP_ID / WECOM_SECRET");

        WeComConfig config = new WeComConfig();
        config.setCorpId(corpId);
        config.setSecret(secret);
        WeComMessageSender sender = new WeComMessageSender(
                config, new RestTemplate(), new ObjectMapper());

        assertThat(sender.getAccessToken()).isNotBlank();
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return v == null ? "" : v.trim();
    }
}

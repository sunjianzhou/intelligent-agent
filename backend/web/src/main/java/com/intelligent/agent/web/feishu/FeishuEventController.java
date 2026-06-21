package com.intelligent.agent.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.service.AgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@Slf4j
@RestController
@RequestMapping("/feishu")
public class FeishuEventController {

    private final FeishuConfig config;
    private final AgentService agentService;
    private final FeishuMessageSender sender;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final FeishuRecallBridge recallBridge;

    @Autowired
    public FeishuEventController(FeishuConfig config,
                                  AgentService agentService,
                                  FeishuMessageSender sender,
                                  ObjectMapper objectMapper,
                                  @Qualifier("feishuStreamExecutor") ExecutorService executor,
                                  FeishuRecallBridge recallBridge) {
        this.config       = config;
        this.agentService = agentService;
        this.sender       = sender;
        this.objectMapper = objectMapper;
        this.executor     = executor;
        this.recallBridge = recallBridge;
    }

    public void routeEvent(String json) {
        try {
            Map<?, ?> frame  = objectMapper.readValue(json, Map.class);
            Map<?, ?> header = (Map<?, ?>) frame.get("header");
            if (header == null) return;

            String eventType = (String) header.get("event_type");
            if (!"im.message.receive_v1".equals(eventType)) {
                log.debug("忽略飞书事件类型: {}", eventType);
                return;
            }

            Map<?, ?> event   = (Map<?, ?>) frame.get("event");
            Map<?, ?> sender_ = (Map<?, ?>) event.get("sender");
            Map<?, ?> sid     = (Map<?, ?>) sender_.get("sender_id");
            String openId     = (String) sid.get("open_id");

            Map<?, ?> message = (Map<?, ?>) event.get("message");
            String chatId     = (String) message.get("chat_id");

            String contentStr = (String) message.get("content");
            String text;
            try {
                Map<?, ?> contentMap = objectMapper.readValue(contentStr, Map.class);
                Object textObj = contentMap.get("text");
                text = textObj != null ? (String) textObj : contentStr;
            } catch (Exception e) {
                text = contentStr;
            }

            String userId = "feishu:" + openId;
            final String finalText   = text;
            final String finalUserId = userId;
            final String finalChatId = chatId;

            executor.submit(() -> {
                try {
                    sender.sendText(finalChatId, "⏳ 思考中...");
                } catch (Exception e) {
                    log.warn("发送「思考中」失败，chatId={}: {}", finalChatId, e.getMessage());
                }

                try {
                    ChatRequest req = new ChatRequest();
                    req.setMessage(finalText);
                    req.setUserId(finalUserId);
                    req.setUseTools(true);
                    req.setUseMemory(true);
                    Map<String, Object> result = agentService.chatFull(req);
                    String reply = String.valueOf(result.getOrDefault("response", ""));
                    String assistantMessageId = (String) result.get("assistant_message_id");
                    String feishuMessageId = sender.sendInteractive(finalChatId,
                            FeishuCardBuilder.textCard("AI 回复", reply));
                    recallBridge.register(assistantMessageId, feishuMessageId);
                } catch (Exception e) {
                    log.error("飞书消息处理失败，chatId={}", finalChatId, e);
                    try {
                        sender.sendText(finalChatId, "⚠️ 处理超时，请重试");
                    } catch (Exception ignored) {}
                }
            });

        } catch (Exception e) {
            log.error("routeEvent 解析失败（跳过本条，不影响 WS 连接）: {}", e.getMessage());
        }
    }

    @PostMapping("/callback/interactive")
    public ResponseEntity<String> handleCardCallback(
            @RequestBody String body,
            HttpServletRequest req) {

        String ts    = req.getHeader("X-Lark-Request-Timestamp");
        String nonce = req.getHeader("X-Lark-Request-Nonce");
        String sig   = req.getHeader("X-Lark-Signature");

        boolean shouldVerify = ts != null && sig != null
                && config.getEncryptKey() != null && !config.getEncryptKey().trim().isEmpty();
        if (shouldVerify && !FeishuCrypto.verifyEventSignature(
                        ts, nonce, config.getVerificationToken(), config.getEncryptKey(), sig)) {
            log.error("飞书卡片回调签名验证失败");
            return ResponseEntity.status(400).body("{\"msg\":\"invalid signature\"}");
        }

        try {
            Map<?, ?> payload   = objectMapper.readValue(body, Map.class);
            Map<?, ?> action    = (Map<?, ?>) payload.get("action");
            Map<?, ?> value     = action != null ? (Map<?, ?>) action.get("value") : null;
            String    actionKey = value  != null ? (String) value.get("key") : null;
            log.info("飞书卡片回调，action_key={}", actionKey);
        } catch (Exception e) {
            log.error("解析卡片回调失败", e);
        }

        return ResponseEntity.ok("{\"msg\":\"ok\"}");
    }
}

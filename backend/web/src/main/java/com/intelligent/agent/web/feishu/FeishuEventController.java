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
import java.util.List;
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

    /** group 场景下、模型判定无需发言时输出的静默约定 sentinel（见 conversation_flow.py [GROUP SCENE] 规则）。*/
    private static final String NO_REPLY_SENTINEL = "NO_REPLY";

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
            String chatType   = (String) message.get("chat_type"); // "p2p" / "group"

            String contentStr = (String) message.get("content");
            String text;
            try {
                Map<?, ?> contentMap = objectMapper.readValue(contentStr, Map.class);
                Object textObj = contentMap.get("text");
                text = textObj != null ? (String) textObj : contentStr;
            } catch (Exception e) {
                text = contentStr;
            }

            boolean isGroup   = "group".equals(chatType);
            boolean mentioned = isGroup && isBotMentioned((List<?>) message.get("mentions"));
            // 群聊里没被 @：跳过「思考中」占位提示，避免对不相关的消息刷屏；
            // 仍会把消息送进 LLM，由 [GROUP SCENE] 规则决定是否真正回复（NO_REPLY 时静默丢弃）。
            boolean quietProbe = isGroup && !mentioned;

            String userId = "feishu:" + openId;
            final String finalText     = text;
            final String finalUserId   = userId;
            final String finalChatId   = chatId;
            final String finalChatType = chatType;
            final boolean finalMentioned  = mentioned;
            final boolean finalQuietProbe = quietProbe;

            executor.submit(() -> {
                if (!finalQuietProbe) {
                    try {
                        sender.sendText(finalChatId, "⏳ 思考中...");
                    } catch (Exception e) {
                        log.warn("发送「思考中」失败，chatId={}: {}", finalChatId, e.getMessage());
                    }
                }

                try {
                    ChatRequest req = new ChatRequest();
                    req.setMessage(finalText);
                    req.setUserId(finalUserId);
                    req.setUseTools(true);
                    req.setUseMemory(true);
                    req.setChannel("feishu_im");
                    req.setSceneChatType(finalChatType);
                    req.setSceneMentioned(finalMentioned);
                    Map<String, Object> result = agentService.chatFull(req);
                    String reply = String.valueOf(result.getOrDefault("response", ""));
                    if (NO_REPLY_SENTINEL.equals(reply.trim())) {
                        log.debug("飞书群聊静默：模型判定无需发言，chatId={}", finalChatId);
                        return;
                    }
                    String assistantMessageId = (String) result.get("assistant_message_id");
                    String feishuMessageId = sender.sendInteractive(finalChatId,
                            FeishuCardBuilder.textCard("AI 回复", reply));
                    recallBridge.register(assistantMessageId, feishuMessageId);
                } catch (Exception e) {
                    log.error("飞书消息处理失败，chatId={}", finalChatId, e);
                    if (!finalQuietProbe) {
                        try {
                            sender.sendText(finalChatId, "⚠️ 处理超时，请重试");
                        } catch (Exception ignored) {}
                    }
                }
            });

        } catch (Exception e) {
            log.error("routeEvent 解析失败（跳过本条，不影响 WS 连接）: {}", e.getMessage());
        }
    }

    /** 判断群聊消息的 mentions 列表中是否包含机器人自身。
     *  未配置 {@code feishu.bot-open-id} 时退化为"群里有人被 @ 就当作可能 @ 了机器人"的低精度启发式。*/
    private boolean isBotMentioned(List<?> mentions) {
        if (mentions == null || mentions.isEmpty()) {
            return false;
        }
        String botOpenId = config.getBotOpenId();
        if (botOpenId == null || botOpenId.isEmpty()) {
            return true;
        }
        for (Object m : mentions) {
            if (!(m instanceof Map)) continue;
            Map<?, ?> mm = (Map<?, ?>) m;
            Map<?, ?> id = (Map<?, ?>) mm.get("id");
            String mentionedOpenId = id != null ? (String) id.get("open_id") : null;
            if (botOpenId.equals(mentionedOpenId)) {
                return true;
            }
        }
        return false;
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

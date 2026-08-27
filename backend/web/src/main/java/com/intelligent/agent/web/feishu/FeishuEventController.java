package com.intelligent.agent.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.ai.agent.approval.ApprovalGate;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.service.AgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
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
    private final ApprovalGate approvalGate;

    /** group 场景下、模型判定无需发言时输出的静默约定 sentinel（见 PromptService [GROUP SCENE] 规则）。*/
    private static final String NO_REPLY_SENTINEL = "NO_REPLY";

    @Autowired
    public FeishuEventController(FeishuConfig config,
                                  AgentService agentService,
                                  FeishuMessageSender sender,
                                  ObjectMapper objectMapper,
                                  @Qualifier("feishuStreamExecutor") ExecutorService executor,
                                  FeishuRecallBridge recallBridge,
                                  ApprovalGate approvalGate) {
        this.config       = config;
        this.agentService = agentService;
        this.sender       = sender;
        this.objectMapper = objectMapper;
        this.executor     = executor;
        this.recallBridge = recallBridge;
        this.approvalGate = approvalGate;
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
            String msgType    = (String) message.get("msg_type");
            String messageId  = (String) message.get("message_id");

            String contentStr = (String) message.get("content");
            String text;
            String emojiType  = null;
            try {
                Map<?, ?> contentMap = objectMapper.readValue(contentStr, Map.class);
                Object textObj = contentMap.get("text");
                text = textObj != null ? (String) textObj : contentStr;
                Object emojiObj = contentMap.get("emoji_type");
                if (emojiObj != null && !String.valueOf(emojiObj).trim().isEmpty()) {
                    emojiType = String.valueOf(emojiObj);
                }
            } catch (Exception e) {
                text = contentStr;
            }

            boolean isGroup   = "group".equals(chatType);
            boolean mentioned = isGroup && isBotMentioned((List<?>) message.get("mentions"));
            // 群聊里没被 @：跳过「思考中」占位提示，避免对不相关的消息刷屏；
            // 仍会把消息送进 LLM，由 [GROUP SCENE] 规则决定是否真正回复（NO_REPLY 时静默丢弃）。
            boolean quietProbe = isGroup && !mentioned;

            // TODO-81 业务判断：群聊收到纯表情消息 → 回点同一表情作为轻量回应，
            // 不再把原始 content JSON 当文本送进 LLM，也不发「思考中」占位。
            if (isGroup && "emoji".equals(msgType) && config.isEmojiReactionEnabled()) {
                try {
                    sender.sendReaction(messageId, emojiType != null ? emojiType : "THUMBSUP");
                    log.debug("飞书群聊表情消息已回点 {}，chatId={}", emojiType, chatId);
                } catch (Exception e) {
                    log.warn("飞书群聊表情消息回应失败，chatId={}: {}", chatId, e.getMessage());
                }
                return;
            }

            String userId = "feishu:" + openId;
            final String finalText     = text;
            final String finalUserId   = userId;
            final String finalChatId   = chatId;
            final String finalChatType = chatType;
            final String finalMessageId = messageId;
            final boolean finalMentioned  = mentioned;
            final boolean finalQuietProbe = quietProbe;

            submitEvent(finalChatId, finalQuietProbe, () -> {
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
                    // R-09：审批卡片回执地址（chat_id，回调决议后确认消息发回同一会话）
                    req.setReplyTo(finalChatId);
                    Map<String, Object> result = agentService.chatFull(req);
                    String reply = String.valueOf(result.getOrDefault("response", ""));
                    if (NO_REPLY_SENTINEL.equals(reply.trim())) {
                        log.debug("飞书群聊静默：模型判定无需发言，chatId={}", finalChatId);
                        // 轻量表情回应代替纯静默（TODO-81）：给用户"已读"反馈又不刷屏
                        if ("group".equals(finalChatType) && config.isEmojiReactionEnabled()
                                && finalMessageId != null) {
                            try {
                                sender.sendReaction(finalMessageId, "THUMBSUP");
                            } catch (Exception e) {
                                log.warn("飞书群聊 NO_REPLY 表情回应失败，chatId={}: {}",
                                        finalChatId, e.getMessage());
                            }
                        }
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

    FeishuConfig getConfig() { return config; }

    /** 提交飞书事件处理任务：有界队列满时拒绝并告知用户，绝不在调用方线程（WS/回调线程）执行长任务。*/
    private void submitEvent(String chatId, boolean quietProbe, Runnable task) {
        try {
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("飞书事件处理队列已满，拒绝消息 chatId={}", chatId);
            if (!quietProbe) {
                try {
                    sender.sendText(chatId, "服务繁忙，请稍后再试");
                } catch (Exception ignored) {}
            }
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
            if (actionKey != null && actionKey.startsWith("approval:")) {
                handleApprovalCallback(payload, actionKey);
            }
        } catch (Exception e) {
            log.error("解析卡片回调失败", e);
        }

        return ResponseEntity.ok("{\"msg\":\"ok\"}");
    }

    /** R-09：审批卡片按钮回调 → ApprovalGate.resolve（userId = feishu:<open_id>）。 */
    private void handleApprovalCallback(Map<?, ?> payload, String actionKey) {
        try {
            String[] parts = actionKey.split(":");
            if (parts.length != 3 || !"approval".equals(parts[0])) {
                return;
            }
            boolean approved = "approve".equals(parts[1]);
            String approvalId = parts[2];
            String openId = String.valueOf(payload.get("open_id"));
            if (openId == null || openId.isBlank() || "null".equals(openId)) {
                Object operator = payload.get("operator");
                openId = operator instanceof Map<?, ?> op
                        ? String.valueOf(op.get("open_id")) : "";
            }
            if (openId == null || openId.isBlank() || "null".equals(openId)) {
                log.warn("飞书审批回调缺少 open_id，approvalId={}", approvalId);
                return;
            }
            String userId = "feishu:" + openId;
            boolean resolved = approvalGate.resolve(approvalId, userId, approved);
            if (!resolved) {
                log.warn("飞书审批决议失败（不存在或用户不匹配），approvalId={}, userId={}",
                        approvalId, userId);
                return;
            }
            sender.sendTextByOpenId(openId, approved ? "✅ 已批准该操作" : "❌ 已拒绝该操作");
            log.info("飞书审批决议成功，approvalId={}, approved={}", approvalId, approved);
        } catch (Exception e) {
            log.error("飞书审批回调处理失败", e);
        }
    }
}

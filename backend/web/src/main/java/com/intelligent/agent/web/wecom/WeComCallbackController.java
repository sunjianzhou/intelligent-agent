package com.intelligent.agent.web.wecom;

import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.service.AgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@RestController
@RequestMapping("/wecom")
public class WeComCallbackController {

    private final WeComConfig       config;
    private final WeComCrypto       crypto  = null; // static 工具类
    private final WeComMessageSender sender;
    private final AgentService      agentService;
    private final ExecutorService   executor;

    /** 已处理消息 ID 去重（LRU 上限 1000 条）。*/
    private final Set<String> processedMsgIds = Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 1000;
                }
            }
    );

    @Autowired
    public WeComCallbackController(WeComConfig config,
                                    WeComMessageSender sender,
                                    AgentService agentService,
                                    @Qualifier("wecomExecutor") ExecutorService executor) {
        this.config      = config;
        this.sender      = sender;
        this.agentService = agentService;
        this.executor    = executor;
    }

    // ── GET：企业微信服务器 URL 验证 ──────────────────────────────

    @GetMapping("/callback")
    public ResponseEntity<String> verify(
            @RequestParam("msg_signature") String msgSignature,
            @RequestParam("timestamp")     String timestamp,
            @RequestParam("nonce")         String nonce,
            @RequestParam("echostr")       String echostr) {

        if (!config.isEnabled()) {
            return ResponseEntity.ok("disabled");
        }

        if (!WeComCrypto.verifySignature(config.getToken(), timestamp, nonce, echostr, msgSignature)) {
            log.error("企业微信 URL 验证签名不匹配");
            return ResponseEntity.status(403).body("invalid signature");
        }

        try {
            String plain = WeComCrypto.decrypt(echostr, config.getAesKey());
            log.info("企业微信 URL 验证通过");
            return ResponseEntity.ok(plain);
        } catch (Exception e) {
            log.error("企业微信 URL 验证解密失败: {}", e.getMessage());
            return ResponseEntity.status(500).body("decrypt error");
        }
    }

    // ── POST：接收消息事件 ─────────────────────────────────────────

    @PostMapping("/callback")
    public ResponseEntity<String> receiveMessage(
            @RequestParam("msg_signature") String msgSignature,
            @RequestParam("timestamp")     String timestamp,
            @RequestParam("nonce")         String nonce,
            @RequestBody String xmlBody) {

        if (!config.isEnabled()) {
            return ResponseEntity.ok("");
        }

        String encrypt;
        try {
            encrypt = extractXmlTag(xmlBody, "Encrypt");
        } catch (Exception e) {
            log.error("企业微信 XML 解析失败: {}", e.getMessage());
            return ResponseEntity.ok("");
        }

        if (!WeComCrypto.verifySignature(config.getToken(), timestamp, nonce, encrypt, msgSignature)) {
            log.warn("企业微信消息签名不匹配，忽略");
            return ResponseEntity.ok("");
        }

        String decrypted;
        try {
            decrypted = WeComCrypto.decrypt(encrypt, config.getAesKey());
        } catch (Exception e) {
            log.error("企业微信消息解密失败: {}", e.getMessage());
            return ResponseEntity.ok("");
        }

        // 立即返回空串，企业微信收到 200 后停止重试；异步处理不阻塞 5s 窗口
        dispatchAsync(decrypted);
        return ResponseEntity.ok("");
    }

    // ── 异步处理 ─────────────────────────────────────────────────

    private void dispatchAsync(String decryptedXml) {
        executor.submit(() -> {
            try {
                String msgType  = extractXmlTag(decryptedXml, "MsgType");
                String fromUser = extractXmlTag(decryptedXml, "FromUserName");
                String msgId    = extractXmlTag(decryptedXml, "MsgId");

                // 消息去重
                if (!processedMsgIds.add(msgId)) {
                    log.debug("企业微信重复消息，忽略，msgId={}", msgId);
                    return;
                }

                if (!"text".equalsIgnoreCase(msgType)) {
                    log.debug("企业微信消息类型 {} 暂不处理，fromUser={}", msgType, fromUser);
                    return;
                }

                String content = extractXmlTag(decryptedXml, "Content");
                log.info("企业微信收到文本消息，fromUser={}, content={}", fromUser,
                        content.length() > 50 ? content.substring(0, 50) + "..." : content);

                ChatRequest req = new ChatRequest();
                req.setMessage(content);
                req.setUserId("wecom:" + fromUser);
                req.setUseTools(true);
                req.setUseMemory(true);
                req.setChannel("wecom");

                Map<String, Object> result = agentService.chatFull(req);
                String reply = String.valueOf(result.getOrDefault("response", ""));
                if (reply.trim().isEmpty()) {
                    log.warn("企业微信 agent 返回空响应，fromUser={}", fromUser);
                    return;
                }

                sender.sendText(fromUser, reply);

            } catch (Exception e) {
                log.error("企业微信消息处理异常: {}", e.getMessage(), e);
            }
        });
    }

    // ── XML 解析工具 ──────────────────────────────────────────────

    private static String extractXmlTag(String xml, String tag) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 防 XXE
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList nodes = doc.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return "";
        Node node = nodes.item(0);
        return node.getTextContent();
    }
}

# 飞书 IM 粘合层设计文档

**版本**：v4.7.7.5 修订 + v4.7.8.1 补充  
**日期**：2026-06-16  
**优先级**：P2（府邸 PWA 主推，飞书为可选通道）  
**作者**：设计评审通过（brainstorming 三节闭环）

---

## 一、背景与目标

现有三层架构：Vue 前端 → Java :8080（WS 网关）→ Python :8000（AI 推理）。

本阶段新增飞书 IM 通道，作为 P2 备选接入点：

- 飞书用户发消息 → Java 接收 → Python Agent 处理 → 飞书回复
- Python Agent 主动推送 → 飞书用户（任务完成、定时通知等）

**永久铁律（v4.7.7.5）**：
- ❌ 不写 `PrePushGuard.java`
- ❌ 不写任何运行时敏感词扫描逻辑
- ❌ 事件路由中不做 channel 维度内容检查

---

## 二、核心决策

| 决策点 | 结论 | 理由 |
|--------|------|------|
| WS 实现 | java-websocket 纯手写（非官方 SDK） | 轻量、精确控制、无 30MB+ 依赖 |
| Python 侧职责 | HTTP Tool（只发不收），注册为 `im_message` | 职责清晰，Java 做 IM 网关，Python 做 AI 推理 |
| 消息类型 | text / post / interactive / image / file / sticker / emoji（7 种） | 覆盖飞书主流消息场景 |
| 用户 ID 隔离 | `feishu:` 前缀（`feishu:ou_xxx`） | 与 Web 用户空间隔离；扩展其他渠道时零成本（`wechat:` / `gh:`） |
| WS 客户端生命周期 | `SmartLifecycle` + `feishu.enabled` 条件启动 | P2 通道不影响主服务；本地无凭据时 0 影响 |
| 响应策略 | 双阶段异步（"思考中..." + 最终回复） | 飞书 3s 回包要求 + Agent 推理 5~30s 不兼容 |

---

## 三、整体架构

```
飞书服务器                          Java :8080                              Python :8000
┌──────────────────┐               ┌─────────────────────────────────┐    ┌──────────────────┐
│ Feishu WS Server │ ←── WS ─────→ │ FeishuWebSocketClient           │    │ AgentService     │
│ wss://open.      │               │   └─ FeishuCrypto（解密）       │───→│ .chat()          │
│ feishu.cn/event  │               │   └─ FeishuEventController      │    └──────────────────┘
│ /v2/websocket/   │               │        └─ userId 前缀拼接       │
│ connect          │               │        └─ FeishuMessageSender   │    ┌──────────────────┐
└──────────────────┘               │          ① 思考中...（立即）    │    │ feishu_client.py │
                                   │          ② 最终回复（异步）     │    │ im_message Tool  │
飞书用户点卡片按钮                   │   └─ FeishuCardBuilder         │    │ 7 种消息类型      │
──── HTTP POST ───────────────→    │ FeishuEventController           │    │ HTTP only        │
POST /feishu/callback/interactive  └─────────────────────────────────┘    └──────────────────┘
```

---

## 四、产物清单（10 项）

| # | 产物 | 位置 |
|---|------|------|
| 1 | `FeishuWebSocketClient.java` | `com.intelligent.agent.web.feishu` |
| 2 | `FeishuEventController.java` | 同上 |
| 3 | `FeishuCrypto.java` | 同上 |
| 4 | `FeishuMessageSender.java` | 同上 |
| 5 | `FeishuCardBuilder.java` | 同上 |
| 6 | `FeishuConfig.java` | 同上 |
| 7 | `agent/im/feishu_client.py` | `agent/im/` |
| 8 | 单测（Java + Python） | `src/test/` + `agent/tests/` |
| 9 | `docker-compose.yml` 飞书环境变量 | 根目录 |
| 10 | `docs/feishu-integration.md` | 根目录 `docs/` |

> ❌ `PrePushGuard.java` 永久废止，不写。

---

## 五、组件设计

### 5.1 FeishuConfig.java

```java
@ConfigurationProperties("feishu")
@Configuration
public class FeishuConfig {
    boolean enabled = false;          // false → 主服务零影响
    String appId;
    String appSecret;
    String encryptKey;                // 可为空（不开启加密时）
    String verificationToken;
    String wsEndpoint = "wss://open.feishu.cn/event/v2/websocket/connect";
    int reconnectDelaySeconds = 5;
    int reconnectMaxDelaySeconds = 300;

    // 独立线程池：5 线程 + 有界队列 100 + CallerRunsPolicy
    @Bean("feishuStreamExecutor")
    public ExecutorService feishuStreamExecutor() { ... }
}
```

**application.yml 追加**：
```yaml
feishu:
  enabled: ${FEISHU_ENABLED:false}
  app-id: ${FEISHU_APP_ID:}
  app-secret: ${FEISHU_APP_SECRET:}
  encrypt-key: ${FEISHU_ENCRYPT_KEY:}
  verification-token: ${FEISHU_VERIFICATION_TOKEN:}
```

---

### 5.2 FeishuCrypto.java（纯静态，0 Spring 依赖）

```java
public final class FeishuCrypto {

    /**
     * 解密飞书 WS payload / HTTP 事件 body。
     * 算法：AES-256-CBC + PKCS7Padding
     *   key = SHA-256(encryptKey)[0:32]
     *   iv  = Base64Decode(cipher)[0:16]
     *   plaintext = AES.decrypt(cipher[16:])
     */
    public static String decrypt(String cipherB64, String encryptKey) { ... }

    /**
     * URL 验证签名（不含 token，不含 body）。
     * 算法：sha256(timestamp + nonce + encryptKey)
     * 适用：飞书开放平台「URL 验证」请求
     */
    public static boolean verifyUrlSignature(
            String timestamp, String nonce, String encryptKey, String expected) { ... }

    /**
     * 事件签名验证（含 token，不含 body）。
     * 算法：sha256(timestamp + nonce + token + encryptKey)
     * 适用：事件订阅推送、卡片回调
     */
    public static boolean verifyEventSignature(
            String timestamp, String nonce, String token, String encryptKey, String expected) { ... }
}
```

> 调用方（`FeishuEventController` / `FeishuWebSocketClient`）持有 `FeishuConfig`，负责传入 `encryptKey`。

---

### 5.3 FeishuWebSocketClient.java（SmartLifecycle）

**启动流程**：
```
isAutoStartup() → feishuConfig.isEnabled()

start()
  → 凭据校验：appId / appSecret 非空，否则 throw IllegalStateException（快速失败）
  → refreshToken()         获取 app_access_token（失败则抛出，容器启动失败）
  → connect(wsEndpoint)    连接失败走指数退避，不阻塞容器

onOpen  → log.info("飞书 WS 已连接")
onMessage(rawFrame)
  → encryptKey 非空 → FeishuCrypto.decrypt(payload, encryptKey)
  → eventController.routeEvent(json)
onClose → scheduleReconnect(currentDelay)
onError → log.error

stop() → wsClient.closeBlocking()
```

**重连状态机**：
```
scheduleReconnect(delay)
  → sleep(delay)
  → ensureTokenValid()   剩余有效期 < 5min → 先 refreshToken()（ReentrantLock 保护）
  → connect(wsEndpoint)
       成功 → currentDelay = 5s（重置）
       失败 → currentDelay = min(currentDelay * 2, 300s)
              scheduleReconnect(currentDelay)
```

---

### 5.4 FeishuEventController.java（双角色）

**角色 A：WS 事件路由（@Component 内部调用）**

```
routeEvent(String json)
  → 解析 event_type
  → "im.message.receive_v1":
       openId = event.sender.sender_id.open_id
       chatId = event.message.chat_id
       text   = event.message.content（去 JSON 外壳）
       userId = "feishu:" + openId
       ① feishuStreamExecutor.submit(() -> {
              // "思考中..." 是 task 第一行：<100ms 启动延迟，用户无感；
              // 不放 WS handler 线程，避免 sendWithRetry 最长 7s 重试阻塞消息处理
              sender.sendText(chatId, "⏳ 思考中...")  // best-effort，失败只 log.warn
              result = agentService.chat(userId, text) // 5~30s
              sender.sendInteractive(chatId,
                  FeishuCardBuilder.textCard("AI 回复", result))
         })
  → 未知 event_type → log.debug 静默丢弃
```

> "思考中..." 走 `sendWithRetry`（3 次），仍然失败则 `log.warn`，**不阻塞**最终回复的异步处理。

**角色 B：HTTP 卡片回调（@RestController）**

```
POST /feishu/callback/interactive
  → FeishuCrypto.verifyEventSignature(ts, nonce, token, encryptKey, sig)
  → 解析 action.value → 路由业务逻辑
  → 200 OK（可选更新卡片内容）
```

---

### 5.5 FeishuMessageSender.java

**Token 管理**（`ReentrantLock` 保护并发刷新）：
```
TokenCache { String token; long expiryMs; }
AtomicReference<TokenCache> tokenRef

getTenantAccessToken()
  → cache.expiryMs - now() < 5min → refreshToken()（加锁）
  → return token

refreshToken() [ReentrantLock]
  → POST /open-apis/auth/v3/tenant_access_token/internal
  → 成功：cache.expiryMs = now + (expire - 300) * 1000
  → 失败：retry 1 次，仍失败则抛出（由调用方决定是否触发重连）

@Scheduled(fixedDelay = 6_600_000)   // 每 110 分钟主动刷新
scheduledRefresh()
```

**发送重试模板**：
```
sendWithRetry(Callable<Void> action, String chatId)
  → for i in 0..2:
       try: action() → return
       catch: sleep(1s << i) → continue
  → fallback: sendText(chatId, "网络繁忙，请重试 🙏")
       catch fallback 失败: log.error("fallback 失败 chatId={}", chatId, e)
                            // 静默丢弃，不抛异常
```

**公开方法**（均走 `sendWithRetry`）：
- `sendText(chatId, text)`
- `sendPost(chatId, Map content)`
- `sendInteractive(chatId, String cardJson)`

---

### 5.6 FeishuCardBuilder.java（纯静态）

```java
public final class FeishuCardBuilder {
    public static String textCard(String title, String content) { ... }
    public static String tableCard(String title, List<Map<String,Object>> rows) { ... }
    public static String buttonCard(String title, String content,
                                    List<Map<String,String>> buttons) { ... }
}
```

返回飞书 Card JSON 字符串，可直接传给 `sendInteractive()`。

---

### 5.7 agent/im/feishu_client.py（Python Tool）

```python
class FeishuIMTool(BaseTool):
    name = "im_message"
    description = """向飞书用户/群组发送消息。
    receive_id_type 四种场景：
      open_id   - 单聊用户（应用维度唯一 ID，推荐）
      union_id  - 跨应用统一用户 ID（多应用共享身份）
      user_id   - 企业内 user_id（需额外权限）
      chat_id   - 群聊（以 'oc_' 开头）
    msg_type 支持：text / post / interactive / image / file / sticker / emoji
    """

    def execute(self,
                receiver_id: str,
                msg_type: str,
                content: dict,
                receive_id_type: str = "open_id") -> dict:
        token = _get_tenant_access_token()  # 从 env FEISHU_APP_ID / FEISHU_APP_SECRET 获取
        resp = requests.post(
            f"{FEISHU_BASE_URL}/open-apis/im/v1/messages",
            params={"receive_id_type": receive_id_type},
            headers={"Authorization": f"Bearer {token}"},
            json={"receive_id": receiver_id,
                  "msg_type": msg_type,
                  "content": json.dumps(content)}
        )
        return resp.json()

# 注册到 ToolManager
tool_manager.register_tool(FeishuIMTool(), category="im")
```

---

## 六、错误处理矩阵

| 场景 | 策略 |
|------|------|
| WS 断线 | 指数退避重连：5s→10s→20s…→300s 上限 |
| Token 过期 | `ReentrantLock` 保护刷新；重连前主动 `ensureTokenValid()` |
| 飞书 API 429/500 | `sendWithRetry`：1s→2s→4s，3 次后发"网络繁忙，请重试 🙏" |
| fallback 自身失败 | `log.error` 记录，静默丢弃，不抛异常 |
| "思考中..." 发送失败 | `log.warn`，不阻塞最终回复的异步处理（best-effort） |
| **加解密失败（协议层）**：decrypt 失败，密钥错 / payload 格式错 | `log.error` + 关闭 WS → 触发重连 |
| **加解密失败（数据层）**：decrypt 成功但 JSON 解析失败 | `log.warn` + 跳过本条消息，**不关闭** WS |
| **签名验证失败**：签名不匹配（疑似协议异常） | `log.error` + 关闭 WS → 触发重连 |
| Agent 响应超时 | 发"⚠️ 处理超时，请重试" |
| `feishu.enabled=false` | `isAutoStartup()` 返回 false，主服务 0 影响 |
| 凭据缺失（enabled=true 但 appId/appSecret 为空） | `start()` 前置校验，`throw IllegalStateException`，快速失败 |

---

## 七、测试计划（目标整体 ≥75%）

### Java 单测

| 类 | 目标覆盖率 | 核心用例 |
|----|-----------|---------|
| `FeishuCrypto` | **100%** | 加解密 roundtrip；URL 签名匹配/不匹配；事件签名匹配/不匹配 |
| `FeishuCardBuilder` | **100%** | 三种卡片 JSON 结构断言 |
| `FeishuMessageSender` | **80%** | token 缓存命中；< 5min 触发刷新；retry 3 次后 fallback；fallback 失败只 log |
| `FeishuEventController` | **70%** | `im.message.receive_v1` 路由；userId 前缀 `feishu:` 断言；未知 type 静默丢弃 |
| `FeishuWebSocketClient` | **60%** | `enabled=false` 不启动；重连 delay 指数增长上限；`ensureTokenValid` 触发刷新 |
| `FeishuConfig` | **80%** | `@ConfigurationProperties` 绑定；`feishuStreamExecutor` Bean 存在 |

### Python 单测

| 文件 | 核心用例 |
|------|---------|
| `test_feishu_client.py` | 7 种消息类型请求体断言（mock HTTP）；4 种 `receive_id_type` 参数校验；token 获取 mock |
| `test_feishu_event.py` | `im_message` 工具注册验证；工具参数 schema 校验 |

### 集成测试（FeishuIntegrationTest.java）

使用 `MockWebServer`（OkHttp）模拟飞书 WS Server + 飞书 OpenAPI，跑通 3 个端到端场景：

1. **WS 消息 → 双阶段回复**：模拟飞书推送 `im.message.receive_v1` → 验证"思考中..."和最终回复均被发出
2. **卡片按钮回调**：模拟 HTTP POST `/feishu/callback/interactive` → 验证签名校验 + 200 响应
3. **主动推送**：模拟 Python Tool 调用 → 验证飞书 API 请求体结构

---

## 八、docker-compose.yml 追加

```yaml
# backend service 的 environment 块追加：
- FEISHU_ENABLED=${FEISHU_ENABLED:-false}
- FEISHU_APP_ID=${FEISHU_APP_ID:-}
- FEISHU_APP_SECRET=${FEISHU_APP_SECRET:-}
- FEISHU_ENCRYPT_KEY=${FEISHU_ENCRYPT_KEY:-}
- FEISHU_VERIFICATION_TOKEN=${FEISHU_VERIFICATION_TOKEN:-}

# agent service 的 environment 块追加：
- FEISHU_APP_ID=${FEISHU_APP_ID:-}
- FEISHU_APP_SECRET=${FEISHU_APP_SECRET:-}
```

---

## 九、依赖变更（pom.xml）

```xml
<!-- 飞书 WS 长连接客户端（纯手写，仅需 java-websocket） -->
<dependency>
    <groupId>org.java-websocket</groupId>
    <artifactId>Java-WebSocket</artifactId>
    <version>1.5.4</version>
</dependency>

<!-- 集成测试：MockWebServer -->
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>mockwebserver</artifactId>
    <version>4.12.0</version>
    <scope>test</scope>
</dependency>
```

> 共新增 2 个依赖，其余均复用现有（Apache HttpClient / Jackson / Spring RestTemplate）。

---

## 十、验收标准

- [ ] WS 连接稳定（30 分钟无断线）
- [ ] AES-256-CBC + PKCS7Padding 加解密与飞书官方示例 100% 对齐
- [ ] 3 种消息类型可发（text / post / interactive）
- [ ] 单测整体覆盖率 ≥ 75%，`mvn package` 成功
- [ ] `FeishuIntegrationTest` 3 个端到端场景全绿
- [ ] `feishu.enabled=false` 时主服务启动 0 影响
- [ ] docker-compose 追加 5 个飞书环境变量

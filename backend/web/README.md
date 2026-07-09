# Java Backend 模块

> 最后更新：2026-07-09（Channel Adapter 抽象层 Java 侧：ChannelAdapter + FeishuChannelAdapter + ChannelAdapterManager）

## 技术栈与运行环境

| 项目 | 版本 |
|------|------|
| **语言** | Java **1.8**（JDK 8，`pom.xml` 中 `<java.version>1.8</java.version>`）|
| Web 框架 | Spring Boot **2.7.18** |
| 构建工具 | Maven 3.6+（项目内置 `mvnw` wrapper）|
| WebSocket | Spring WebSocket（`TextWebSocketHandler`）|
| HTTP 客户端 | Apache HttpClient 4.5 |
| JSON | Jackson 2.11 |
| 测试 | JUnit 5（`./mvnw test`）|

> **JDK 8 限制**：`Map.of()`、`List.of()` 等 Java 9+ API 不可用，多值 Map 须用 `new HashMap<>()` 或 `Collections.singletonMap()`。

---

> Spring Boot 2.7 服务，port 8080。纯粹的 WebSocket 网关 + HTTP 代理层，不含任何 AI 业务逻辑。

---

## 定位与职责

| 职责 | 说明 |
|------|------|
| WebSocket 网关 | 接收前端 WS 消息，异步转发至 Python SSE 流，推送 token / 工具事件 / 任务更新 |
| JWT 认证 | 前端 JWT 验证；向 Python 发请求时使用服务间 token（`java-service`）|
| HTTP 代理 | 所有 `/api/*` 请求透明代理至 Python Agent（附带 jwt 鉴权）|
| 模型 / 角色切换 | HealthController + PersonaProxyController 封装后转发至 Python |

**不在这里做的事**：LLM 推理、记忆存取、工具执行——这些全在 Python。

---

## 目录结构

```
backend/web/src/main/java/.../
├── controller/
│   ├── WebSocketController.java           WS 消息路由（chat_message / ping / get_system_info）
│   ├── ChatController.java                /api/chat（非 WS 同步聊天，供飞书/直接 REST 调用）
│   ├── HealthController.java              /api/health、/api/models、/api/model/switch、/api/config/*
│   ├── AuthController.java                /api/auth/login、/api/auth/refresh
│   ├── RoleController.java                /api/roles/* → Python（角色 CRUD + 激活状态）
│   ├── ConversationsProxyController.java  /api/conversations/* → Python（历史会话+撤回，撤回成功后调用 FeishuRecallBridge）
│   ├── MemoryProxyController.java         /api/memory/* → Python
│   ├── TaskProxyController.java           /api/tasks/* → Python
│   ├── ToolProxyController.java           /api/tools/* → Python
│   ├── SkillProxyController.java          /api/skills/* → Python
│   ├── AnalyticsProxyController.java      /api/analytics/* → Python
│   ├── ImageProxyController.java          /api/images/* → Python
│   ├── ProjectProxyController.java        /api/project/* → Python（spec CRUD、context 查询）
│   ├── CloudProxyController.java          /api/cloud/* → Python（云端服务商 CRUD + 激活切换）
│   └── SpaController.java                 兜底路由（Vue Router history mode）
├── feishu/                          飞书（Lark）IM 集成（长连接 WebSocket，无需公网/HTTPS）
│   ├── FeishuConfig.java             飞书 app_id/secret/verification token 配置
│   ├── FeishuWebSocketClient.java    飞书长连接客户端（SmartLifecycle + 重连状态机）
│   ├── FeishuEventController.java    接收飞书消息事件，调用 AgentService.chatFull() 生成回复
│   ├── FeishuMessageSender.java      发送文本/卡片消息，返回飞书 message_id；recall() 撤回 API；verifyMessageContent()/verifyMessageId() 推送前后自查
│   ├── FeishuCardBuilder.java        飞书交互卡片 JSON 构建
│   ├── FeishuCrypto.java             飞书事件签名验证
│   ├── FeishuRecallBridge.java       内部 assistant_message_id ↔ 飞书 message_id 映射（内存态，封顶500条），撤回时联动调用 recall()
│   └── FeishuOAuthController.java    /feishu/oauth/* 代理（callback 无 JWT / authorize+status 有 JWT，proxyGetRaw() 透传 HTML）
├── im/                              Channel Adapter 抽象层（Java 侧，与 Python im/ 对应）
│   ├── ChannelAdapter.java          Java 侧 ChannelAdapter 接口（sendText/sendCard/sendMessage）
│   ├── FeishuChannelAdapter.java    飞书适配器实现（委托 FeishuMessageSender）
│   ├── ChannelAdapterManager.java   Spring Bean（管理 adapter 注册 + broadcast() 并行广播 + 生命周期）
│   ├── ChannelType.java / ChannelMessage.java / SendResult.java  跨 channel 统一数据模型
│   ├── UserInfo.java / RetryConfig.java / TokenBucket.java       用户信息 + 重试配置 + 令牌桶限流
│   └── ChannelMetric.java           单 channel 发送指标（成功率/延迟/限流拒绝）
├── service/
│   ├── AgentService.java            Python SSE 流读取 + WS 推送（线程池），done 事件转发 user_message_id/assistant_message_id
│   └── PythonProxyService.java      通用 HTTP 代理（GET/POST/PUT/PATCH/DELETE）
├── filter/
│   └── JwtAuthFilter.java           JWT 验证 + X-New-Token 滑动续期
├── util/
│   ├── JwtUtil.java                 JWT 签发 / 解析
│   └── JsonUtil.java                WS 消息序列化工具
└── dto/
    ├── request/ChatRequest.java     含 message、use_tools、use_memory、project_id、pending_tasks
    └── WebSocketMessageType.java    事件类型常量
```

---

## WebSocket 消息协议

### 前端 → Java（发送）

```json
{ "type": "chat_message", "message": "用户消息", "use_tools": true, "use_memory": true,
  "project_id": "proj-xxx", "pending_tasks": [...] }
{ "type": "ping", "request_id": "req-xxx" }
{ "type": "get_system_info" }
```

### Java → 前端（接收）

| type | 含义 | 关键字段 |
|------|------|---------|
| `connection_established` | WS 连接成功 | — |
| `thinking` | AI 开始推理 | `request_id` |
| `chat_token` | 流式 token | `data`（token 文本）|
| `tool_call_start` | 工具开始执行 | `tool_name`、`args_summary` |
| `tool_calls_done` | 本轮工具全部完成 | `data`（工具调用列表）|
| `chat_done` | 本轮完整回复 | `content`、`response_time_ms`、`user_message_id`、`assistant_message_id`（用于前端撤回功能定位消息）|
| `task_update` | 任务完成 | `task_data.task_id`、`project_id` |
| `task_blocked` | 任务阻塞 | `task_data.task_id`、`project_id` |
| `pong` | 心跳回复 | — |
| `system_info` | 系统信息 | `data`（健康 + 模型信息）|
| `error` | 异常 | `message` |

---

## 关键实现细节

### 流式聊天（AgentService）

```
WebSocketController.handleChatMessage()
    │ threadPool.submit()
    ▼
AgentService.doStreamChat()
    │ HTTP POST /api/chat/stream（Python SSE）
    │ CloseableHttpClient（独立 HTTP 客户端，读超时 600s）
    ▼
逐行读 SSE → 解析 JSON → 构造 WS 消息 → JsonUtil.sendJsonMessage(session, msg)
```

**注意事项**：
- 流式使用独立的 `CloseableHttpClient`（非 RestTemplate），避免影响普通 REST 请求
- `streamHttpClient` 连接池：`PoolingHttpClientConnectionManager`，max-per-route=20，socket timeout=620s（防 Python 挂住时线程永久阻塞）
- `streamExecutor`：专用线程池（核心2/最大10/队列100），队列满时返回 503（AbortPolicy）而不是卡 Tomcat 线程
- `session.isOpen()` 检查：客户端断连时及时终止流读取
- 任务通知推送：`@Scheduled(fixedDelay=5000)` 主动轮询 Python `/api/notifications/poll` 并 broadcast WS `notification` 事件，取代前端 30s 轮询

### JWT 服务间 token

```java
// 所有向 Python 的请求都携带此 token（Python 解析 sub="java-service"）
serviceToken = jwtUtil.generateToken("java-service");
```

token 统一由 `PythonProxyService.getServiceToken()` 管理（单一来源），临近过期（5 分钟内）自动刷新。`AgentService` 不再维护独立 token。

**用户 ID 透传（已完成 P0 改造）**：
- `JwtHandshakeInterceptor`：WebSocket 握手时解码前端 JWT，提取 `sub` 字段存入 session attributes
- `WebSocketController`：从 session attributes 读取 userId，写入 `ChatRequest.userId`
- `AgentService.doStreamChat()`：从 ChatRequest 读取 userId，加 `X-User-Id` 请求头
- `PythonProxyService.authHeaders(userId)`：所有 REST 代理端点携带 `X-User-Id` 头
- 所有代理 Controller（Memory/Task/Persona/Tool/Skill/Analytics/Project）：通过 `proxy.extractUserIdFromRequest(req)` 提取并透传用户身份
- Python Agent：middleware 读取 `X-User-Id`，当 JWT sub 是 java-service 时以此为 user_id

**效果**：每个前端用户（admin/user1/user2 等）拥有独立的模型偏好和角色偏好，互不干扰。

### JWT 滑动续期

`JwtAuthFilter` 在每次有效请求后在响应头写入 `X-New-Token`，前端自动更新本地 token，实现无感续期（24h token，活跃用户永不过期）。

### Channel Adapter（IM Channel 抽象层）

```
ChannelAdapterManager（Spring Bean）
    │  管理 adapter 注册 + broadcast() 并行广播
    ▼
FeishuChannelAdapter implements ChannelAdapter
    │  委托 FeishuMessageSender，统一 sendText/sendCard/sendMessage
    │
    └── ChannelMetric 指标（成功率/延迟/限流拒绝次数）
```

Java 侧提供与 Python `agent/im/` 对应的 Channel Adapter 层：
- `ChannelAdapter` 接口：定义 `sendText()` / `sendCard()` / `sendMessage()` 三个方法
- `FeishuChannelAdapter`：实现类，内部委托 `FeishuMessageSender`，保持向后兼容
- `ChannelAdapterManager`：Spring `@Component`，管理所有 adapter 的注册/注销 + `broadcast()` 并行广播（多通道同时发送，失败隔离不影响其他通道）
- 数据模型（`ChannelType` / `ChannelMessage` / `SendResult` / `UserInfo` / `RetryConfig` / `TokenBucket` / `ChannelMetric`）与 Python 侧一一对应

---

## 配置项（application.yml）

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `intelligent-agent.python-service.base-url` | `http://localhost:8000` | Python Agent 地址；Docker 中为 `http://agent:8000` |
| `auth.jwt.secret` | 通过 `JWT_SECRET` 环境变量注入 | 与 Python 保持一致，≥32 字符 |
| `auth.jwt.expiry-hours` | `24` | token 有效期 |
| `auth.users` | admin / user 各一个 | 用户名 + 密码配置 |
| `spring.websocket.allowed-origins` | `*` | WS CORS |
| `LOG_LEVEL` | `WARN` (Docker) / `INFO` (本地) | 日志级别 |

---

## 启动与开发

```bash
cd backend/web

# 启动（使用项目内置 Maven wrapper）
# wrapper 路径：E:\workspace\llm\mock_webflux\maven-dist\apache-maven-3.9.6\bin\mvn.cmd
./mvnw spring-boot:run

# 仅编译
./mvnw compile

# 打包
./mvnw package -DskipTests

# 测试
./mvnw test
```

**环境变量注入（本地）**：
```powershell
$env:PYTHON_SERVICE_BASE_URL = "http://localhost:8000"   # 或 8001
./mvnw spring-boot:run
```

日志输出文件：`spring.log`（stdout）、`spring.log.err`（stderr）。

---

## 已知问题与技术债

| 编号 | 问题 | 状态 |
|------|------|------|
| J-01 | `java-service` 固定 token，无法向 Python 透传真实用户 | ✅ 已修复（2026-06-02）：所有 Controller 提取并透传 X-User-Id |
| J-02 | `PythonProxyService` 和 `AgentService` 各维护独立 serviceToken，重复逻辑 | ✅ 已合并：`PythonProxyService.getServiceToken()` 为单一来源，临近过期自动刷新 |
| J-03 | 流式 `CloseableHttpClient` 无连接池配置，高并发可能连接耗尽 | 已配置 `PoolingHttpClientConnectionManager`（max-per-route=20），低优先级 |
| J-04 | WebSocket 握手无 JWT 验证 | ✅ 已修复：`JwtHandshakeInterceptor` 在握手阶段校验 token，提取 userId 存入 session attributes |

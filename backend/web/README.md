# Java Backend 模块

> 最后更新：2026-08-26（Java-only 单后端：Python 回滚路径已移除；2026-08-22 完成高并发优化
> ——异步 REST/流式并发上限/推理闸门超时与按模型分槽/向量记忆按用户分文件/技能运行时注入；
> 2026-08-26 新增 R-07 子代理/多代理编排并真机验证（qwen2.5:7b））

## 技术栈与运行环境

| 项目 | 版本 |
|------|------|
| **语言** | Java **21**（JDK 21，`pom.xml` 中 `<java.version>21</java.version>`）|
| Web 框架 | Spring Boot **3.5.16** |
| 构建工具 | Maven 3.6+（项目内置 `mvnw` wrapper）|
| WebSocket | Spring WebSocket（`TextWebSocketHandler`）|
| HTTP 客户端 | Apache HttpClient 5 / java.net.http |
| JSON | Jackson 2.18 |
| 测试 | JUnit 5（`./mvnw test`）|

---

> Spring Boot 3.5 服务，port 8080。唯一服务端：WebSocket 网关 + 全部 AI 逻辑
> （记忆/RAG/领域 API/调度/Channel 集成）。Java-only 单后端，无 Python 回退路由。

---

## 定位与职责

| 职责 | 说明 |
|------|------|
| WebSocket 网关 | 接收前端 WS 消息，本地 ReAct 编排（`AgentOrchestrator` 流） |
| JWT 认证 | 前端 JWT 验证（REST `JwtAuthFilter` + WS 握手 `JwtHandshakeInterceptor`）|
| 领域 API | role/conversation/project/task/knowledge/skill/analytics/teaching 本地服务 |
| 记忆与 AI | 短期会话/蒸馏/摘要/语义缓存 + ReAct 编排 + Ollama/云端 LLM + 工具内核 |
| 调度与集成 | TaskSchedulerService + Feishu/WeCom/Telegram/ComfyUI/MCP + 幂等广播 |

**架构**：Java-only 单后端；Python 服务与回滚路径已移除，控制器直接调用本地领域服务。

---

## 目录结构

```
backend/web/src/main/java/.../
├── controller/
│   ├── WebSocketController.java           WS 消息路由（chat_message / ping / get_system_info）
│   ├── ChatController.java                /api/chat（非 WS 同步聊天，供飞书/直接 REST 调用）
│   ├── HealthController.java              /api/health、/api/models、/api/model/switch、/api/config/*
│   ├── AuthController.java                /api/auth/login、/api/auth/refresh
│   ├── RoleController.java                /api/roles/* → 本地角色服务
│   ├── ConversationsProxyController.java  /api/conversations/* → 本地会话服务（撤回联动 FeishuRecallBridge）
│   ├── MemoryProxyController.java         /api/memory/* → 本地记忆服务
│   ├── TaskProxyController.java           /api/tasks/* → 本地调度服务
│   ├── ToolProxyController.java           /api/tools/* → 本地工具注册表
│   ├── SkillProxyController.java          /api/skills/* → 本地技能服务
│   ├── AnalyticsProxyController.java      /api/analytics/* → 本地统计服务
│   ├── ImageProxyController.java          /api/images/* → 本地 ImageService（ComfyUI）
│   ├── ProjectProxyController.java        /api/project/* → 本地项目服务（spec CRUD、context 查询）
│   ├── CloudProxyController.java          /api/cloud/* → 本地云端服务商管理
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
├── im/                              Channel Adapter 抽象层（飞书/企微/Telegram/Web 通道）
│   ├── ChannelAdapter.java          Java 侧 ChannelAdapter 接口（sendText/sendCard/sendMessage）
│   ├── FeishuChannelAdapter.java    飞书适配器实现（委托 FeishuMessageSender）
│   ├── ChannelAdapterManager.java   Spring Bean（管理 adapter 注册 + broadcast() 并行广播 + 生命周期）
│   ├── ChannelType.java / ChannelMessage.java / SendResult.java  跨 channel 统一数据模型
│   ├── UserInfo.java / RetryConfig.java / TokenBucket.java       用户信息 + 重试配置 + 令牌桶限流
│   └── ChannelMetric.java           单 channel 发送指标（成功率/延迟/限流拒绝）
├── service/
│   ├── AgentService.java            本地流式聊天编排 + WS 推送（线程池），done 事件转发 user_message_id/assistant_message_id
│   └── AgentService.java            本地聊天编排 + WS 事件映射
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
| `plan` | 复杂任务执行计划（G6/R-07） | `data.steps`（含 `group` 并行分组）|
| `approval_required` | 工具调用需用户审批（HITL） | `data`（approval_id/tool/args）|
| `citation` | 知识问答引用来源（R-05） | `data`（file_id/filename/chunk_index/label）|
| `model_fallback` | 模型降级（R-02） | `data`（from/to/reason）|
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
    │ streamChatAsync()：ActiveChatLimiter.tryAcquire()（满 → 回"服务繁忙"）
    ▼
AgentService.localStreamChat()
    │ 本地 AgentOrchestrator.stream()（Reactor，阻塞 IO 在 boundedElastic）
    │ JDK HttpClient 流式读 Ollama NDJSON（逐 token）
    ▼
逐行读 SSE → 解析 JSON → 构造 WS 消息 → JsonUtil.sendJsonMessage(session, msg)
```

**注意事项**：
- LLM 调用统一走 `AbstractHttpLlmProvider`（JDK HttpClient，`subscribeOn(boundedElastic)`），
  不再有独立 CloseableHttpClient 流式客户端
- 非流式 `/api/chat` 异步化：`chatExecutor`（8/32/队列200）承接最长 620s 推理，Tomcat 线程立即释放，满时 503
- 流式并发上限：`ActiveChatLimiter`（WS/SSE 共用，默认 32）在入口 tryAcquire，超限直接回"服务繁忙"
- 推理闸门：`InferenceGate` 按模型分槽 + 排队超时（`LLM_INFERENCE_QUEUE_TIMEOUT` 默认 120s）
- 槽位释放：`AgentService` 的订阅统一 `doFinally` 释放（complete/error/cancel 只一次）
- `session.isOpen()==false` 时主动 `dispose()` 下游推理流，断线不再白占 boundedElastic 与闸门槽位
- 技能运行时注入：`SkillMatcher` 命中后向上下文插入 `[SKILL]` 系统消息并过滤本轮工具集
- 任务通知推送：本地 `TaskSchedulerService` 通知队列广播 WS `notification` 事件

### 子代理编排（R-07）

复杂计划（≥2 步）由 `SubAgentExecutor` 按 `group` 并行派发给只读研究子代理：

- `PlanStep.group`：相同正整数归入同一并行组（组间按序），≤0 串行；`LlmTaskPlanner`
  提示词/解析器支持 group，并对本地模型的嵌套 JSON 计划做递归展开（真机实测形态）
- 每个子代理独立 `AgentRequestContext` + 只读工具白名单（`ai.subagent.tools`），白名单外
  工具（含副作用工具）执行时强制 `denied`；共享记忆仓库只读召回（episodic/semantic，
  不注入主对话历史）；最多 `maxRounds` 轮工具后无工具收尾
- 单步失败/超时隔离为该步骤 error 结果，不中断整体；整组执行失败自动降级为原
  [PLAN] 注入 + 主线程工具轮路径
- trace 新增 `sub_agent` span（step/title/status/duration_ms/chars）
- 默认 `ai.subagent.timeout=120s`、组级等待 = 超时 × maxRounds（本地 qwen2.5:7b 单次
  LLM 调用实测 40~65s；真机验证：4 步计划 group 1/1/1/0、三子代理同 start 并行、
  结果含真实计算与北京时间、trace `sub_agent` span 全 ok）

### 用户 ID 提取（Java-only）

- `JwtHandshakeInterceptor`：WebSocket 握手时解码前端 JWT，提取 `sub` 字段存入 session attributes
- `WebSocketController`：从 session attributes 读取 userId，写入 `ChatRequest.userId`
- 用户 ID：`JwtAuthFilter` 鉴权后写入 request attribute `userId`，控制器经 `UserContext.userId(req)` 读取

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

Channel Adapter 层（设计对齐旧 Python im/ 实现）：
- `ChannelAdapter` 接口：定义 `sendText()` / `sendCard()` / `sendMessage()` 三个方法
- `FeishuChannelAdapter`：实现类，内部委托 `FeishuMessageSender`，保持向后兼容
- `ChannelAdapterManager`：Spring `@Component`，管理所有 adapter 的注册/注销 + `broadcast()` 并行广播（多通道同时发送，失败隔离不影响其他通道）
- 数据模型（`ChannelType` / `ChannelMessage` / `SendResult` / `UserInfo` / `RetryConfig` / `TokenBucket` / `ChannelMetric`）

---

## 配置项（application.yml）

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `intelligent-agent.data-dir` | `data` | 领域服务 JSON 持久化目录 |
| `auth.jwt.secret` | 通过 `JWT_SECRET` 环境变量注入 | ≥32 字符，Java 后端签发与验证 |
| `auth.jwt.expiry-hours` | `24` | token 有效期 |
| `auth.users` | admin / user 各一个 | 用户名 + 密码配置 |
| `spring.websocket.allowed-origins` | `*` | WS CORS |
| `LOG_LEVEL` | `WARN` (Docker) / `INFO` (本地) | 日志级别 |
| `ai.chat.max-concurrent-streams` | `32` | WS/SSE 流式对话并发上限（runtime `stream_concurrency` 可调）|
| `ai.llm.inference-queue-timeout` | `120s` | 推理闸门排队超时 |
| `ai.skills.runtime-enabled` | `true` | 技能运行时匹配/注入开关 |
| `ai.skills.llm-timeout` | `10s` | 技能 LLM 意图裁决超时 |
| `ai.subagent.enabled` | `true` | 子代理编排开关（R-07） |
| `ai.subagent.pool-size` | `4` | 子代理线程池大小 |
| `ai.subagent.queue-size` | `32` | 子代理排队容量（满时拒绝）|
| `ai.subagent.timeout` | `120s` | 子代理单次 LLM 调用超时 |
| `ai.subagent.max-rounds` | `3` | 单个子代理最大工具轮次 |
| `ai.subagent.max-result-chars` | `2000` | 单子代理结果截断字符数 |
| `ai.subagent.tools` | 只读研究工具集 | 子代理工具白名单（空 = 无工具）|
| `spring.mvc.async.request-timeout` | `660000ms` | 异步 REST 最长等待（须覆盖 chat_timeout 600s）|
| `spring.task.execution.pool.max-size` | `32` | MVC 异步（SSE）执行器线程上限 |

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

# E2E（需 backend + Ollama 运行）
./mvnw.cmd -f ../../tests/e2e-java/pom.xml test

# 压测/基线（需 backend + Ollama 运行）
./mvnw.cmd -f ../../tests/perf-java/pom.xml test -Dgroups=perf -DexcludedGroups= -Dperf.saveBaseline=target/perf-report/baseline.json
```

**环境变量注入（本地）**：
```powershell
$env:JWT_SECRET = "你的随机密钥"
$env:ADMIN_PASSWORD = "你的管理密码"
./mvnw spring-boot:run
```

日志输出文件：`spring.log`（stdout）、`spring.log.err`（stderr）。

---

## 已知问题与技术债

| 编号 | 问题 | 状态 |
|------|------|------|
| J-01 | `java-service` 固定 token，无法向 Python 透传真实用户 | ✅ 已随 Python 回滚路径移除；用户 ID 由 JwtAuthFilter request attribute + UserContext 提供 |
| J-02 | `PythonProxyService` 和 `AgentService` 各维护独立 serviceToken，重复逻辑 | ✅ 已随 Python 回滚路径移除（2026-08-11） |
| J-03 | 流式 HTTP 客户端连接管理 | ✅ 已解决：LLM provider 统一 JDK HttpClient + Reactor boundedElastic；外部集成用 HttpClient 5 连接池（max 50/route 20）|
| J-04 | WebSocket 握手无 JWT 验证 | ✅ 已修复：`JwtHandshakeInterceptor` 在握手阶段校验 token，提取 userId 存入 session attributes |

# TODOS

这份文件记录已明确但暂未纳入当前 PR 的工作项。每一条都包含足够上下文，任何人拿起来都能知道从哪里开始。

---

## TODO-1: HTTPS/TLS — 生产环境全程 HTTP 明文

**什么**: 当前整个栈（前端 → Java → Python）全程 HTTP。在网络传输级别，Token 和聊天内容仍为明文。

**为什么**: Token + 聊天内容明文传输是 OWASP A02:2021 加密失效风险。若部署到内网之外或多设备访问，必须解决。

**如何实现**: docker-compose 前加一层 Nginx 反向代理，配置 Let's Encrypt 自动证书。Java/Python 后端无需改动，Nginx 终结 TLS。

**当前状态**: 项目属于本地优先开发阶段，暂不影响开发体验。

---

## TODO-12: 性能优化剩余项（⏸️ 待触发条件）

以下项目已确认可做，但当前规模不值得操作，待对应触发条件出现再处理：

4. **L1 响应缓存锁优化** — `agent/core/agent.py:108-109`；需 `inference_concurrency` 调高后出现热点再做。
5. **Java 侧 token 批量转发** — 需先有延迟抖动证据再做。
8. **Scheduler 轮询改事件驱动** — 任务量小时无所谓；调度器历史上多次出现并发/绑定 bug，改动有风险，待任务量真正增长再做。

> ChromaDB 迁移至 Docker 具名卷已于 2026-06-09 完成，具名卷为 `intelligent_agent_agent_chroma_data` / `intelligent_agent_agent_chroma_data_longterm`。
> 中间无前缀卷 `agent_chroma_data`/`agent_chroma_data_longterm` 可按需 `docker volume rm` 清理。

---

## TODO-20: Docker 中间无前缀卷清理（LOW）

**什么**: ChromaDB 迁移过程（2026-06-09）创建了两个无项目前缀的中间卷：`agent_chroma_data` 和 `agent_chroma_data_longterm`。数据已迁移到正式卷（`intelligent_agent_agent_chroma_data` / `intelligent_agent_agent_chroma_data_longterm`），中间卷空间仍占用。

**如何清理**:
```bash
docker volume rm agent_chroma_data agent_chroma_data_longterm
```

**注意**: 执行前先确认无容器正在挂载这两个卷：
```bash
docker ps -a --filter volume=agent_chroma_data
```

**代价**: Human ~5min / CC ~2min

---

## TODO-21: Feishu/微信 Bot 接入（MEDIUM，待公网环境）

**什么**: 实现飞书 / 微信 Bot Webhook 接入点，让用户在已有工作流中直接与 AI 对话，无需打开 Web UI。

**为什么**: 将产品从"个人工具"变为"可分享的 AI 助手"，使用频率可能显著提升。

**如何实现**: 在 Python FastAPI 新增 `POST /api/webhook/feishu`，验证飞书 AppSecret 签名，将消息正文转发给现有 `POST /api/chat`，返回结果。飞书/微信两者独立实现，代码可复用同一适配层。

**当前状态**: 需要公网 IP 和 Bot 平台审核，本地开发环境无法验证。待部署到可公网访问的服务器后再做。

**先决条件**: TODO-1（HTTPS/TLS）完成后方可上线（Bot 平台要求 HTTPS 回调）。

**代价**: Human ~1天 / CC ~45min

---

## TODO-22: agent/core/agent.py God Class 拆构（HIGH，先测试再动）

**什么**: 将 `agent/core/agent.py`（2318 行）拆分为三个单一职责类：
- `ConversationFlow` — 对话主流程（prompt 组装、消息发送、回应流）
- `ToolDispatcher` — 工具解析、规划、执行
- `MemoryWriter` — 记忆写入、提炼、上下文拼接

**为什么**: 该文件是历史上所有崩溃性 bug 的集中地（Semaphore 绑定错误、内容字段 key、指滞器等）。单一文件承载过多职责，每次修改都是全量风险。

**如何实现**: 按类边界抽取方法，保持 `IntelligentAgent` 作为门面类（Facade），各子类独立可测试。

**先决条件**: **必须先完成 agent.py 核心测试覆盖（CEO Review T5）**，确保有安全网后再拆构。在无测试保护的情况下拆构等同于在生产上盲目重构。

**代价**: Human ~1天 / CC ~1h

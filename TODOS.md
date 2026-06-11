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

## ~~TODO-22: agent/core/agent.py God Class 拆构~~ ✅ 已完成（2026-06-11）

**结果**: 2318 行 God Class 已拆分为四个文件（commit `528b787`）：
- `core/_context_vars.py`    — 共享 ContextVar（避免循环导入）
- `core/memory_writer.py`    — MemoryWriterMixin（预热/MCP/蒸馏/清理，~310行）
- `core/tool_dispatcher.py`  — ToolDispatcherMixin（工具注册/意图/LLM调用，~1130行）
- `core/conversation_flow.py` — ConversationFlowMixin（消息构建/chat/stream，~460行）
- `core/agent.py`            — 薄门面 IntelligentAgent（__init__/provider/token/cache，~320行）

152 passed，1 个预存 flaky（cron 时序竞争），无新增失败。

---

## ~~TODO-23: 前端 P1 视觉美化~~ ✅ 已完成（2026-06-11）

基于浏览器真实访问的视觉评审，commit `5930a86`：

- **ChatView.vue** — 4张示例卡片蓝/橙/绿/紫配色主题（nth-child）；无限制 badge 独立换行
- **MemoryView.vue** — 刷新/导入/恢复三个纯图标按钮补文字标签，icon+text 间距 5px
- **TasksView.vue** — task-desc 截断至 2 行（`-webkit-line-clamp: 2`），与 task-prompt 一致
- **ProjectView.vue** — spec-panel 加右边框，三列视觉分隔完整

---

## ~~TODO-24: Header 信息密度优化~~ ✅ 已完成（2026-06-12）

模型切换器从 Header 移至 ChatView 输入框上方 config-bar；同行新增角色选择器（调用 `/api/roles/activate`）；Header 只保留连接状态/主题/清空/管理齿轮。

---

## ~~TODO-25: MemoryView 清空全部按钮视觉分离~~ ✅ 已完成（2026-06-12）

「清空全部」按钮从 `stats-row` 移出，独立为 `danger-zone` div，用 `border-top: 1px solid #fecaca` 分隔。

---

## ~~TODO-26: SystemView 信息展示优化~~ ✅ 已完成（2026-06-12）

「可用模型」和「内存优化建议」两张冗长卡片改为可折叠（点标题展开/收起），默认建议折叠、模型展开。

---

## ~~TODO-27: 历史对话侧边栏 UX 完善~~ ✅ 已完成（2026-06-12）

1. 无 preview 时显示「新对话」占位；2. 删除当前会话后自动加载下一条；3. 移动端面板宽度改 `min(240px, 85vw)`。

---

## ~~TODO-28: 角色编辑器 Markdown 预览~~ ✅ 已完成（2026-06-12）

RoleEditorView 新增第六个 Tab「提示预览」，用 `marked` + `DOMPurify` 实时渲染角色表单为 Markdown 系统提示预览。

---

## ~~TODO-29: 移动端汉堡菜单完整性~~ ✅ 已完成（2026-06-12）

Header.vue navItems 补「系统」(`/admin/system`)；聊天页抽屉新增「历史会话」项，点击触发 `store.triggerOpenHistory()`，ChatView watch 信号自动打开面板。

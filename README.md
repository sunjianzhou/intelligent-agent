# Intelligent Agent System

> 本地优先的 AI 智能体平台：Ollama 本地推理 · Spring Boot 单后端（WebSocket + REST + 全部 AI 逻辑）· Vue 3 聊天界面 · Java CLI 客户端
> 支持多工具调用、长期记忆、任务调度、多角色切换、项目上下文持久化。
> 最后更新：2026-08-08（W13 Java 统一迁移完成：Python Agent 已退役，全部逻辑并入 Java 单后端）

```
浏览器 / CLI 客户端
        │  WebSocket (流式) + REST
        ▼
┌────────────────────────────────────────────────┐
│  Java 后端 (Spring Boot :8080)                │
│   JWT 鉴权 · WS 管理 · 记忆/RAG · 领域 API    │
│   任务调度 · Channel 集成 · 语义缓存 · 工具内核 │
└───────────────────┬────────────────────────────┘
                    │
              ┌─────┴──────┐
              ▼            ▼
           Ollama      向量存储
          (:11434)   (内存/迁移后持久化)
           本地 LLM      长期记忆
```

---

## 启动速查

### 前置：配置文件（必须，不在 git 里）

```bash
cp .env.example        .env           # compose 级变量（JWT_SECRET、ADMIN_PASSWORD 等）
cp .env.docker.example .env.docker    # 容器运行时变量（含 IM 集成、云端 LLM 等）
```

两个文件都要填写 `JWT_SECRET`（≥32 字符随机串）和 `ADMIN_PASSWORD`，其余按需填写。  
**第三方拿到此仓库后同样需要完成此步骤，否则所有 docker compose 命令均无法运行。**

### 按需选择启动命令

| 命令 | 启动的服务 | 适用场景 |
|------|-----------|---------|
| `docker compose up -d --build` | backend · frontend | 纯本地使用，云端 LLM 或不需要公网（Java 单后端） |
| `docker compose --profile tunnel up -d --build` | backend · frontend · **cloudflared** | 需要公网访问（企业微信/飞书回调），使用云端 LLM |
| `docker compose --profile local up -d --build` | backend · frontend · **ollama · comfyui** | 本地 GPU 推理，无需公网 |
| `docker compose --profile local --profile tunnel up -d --build` | 全部服务 | 本地 GPU 推理 + 公网隧道 |
| `start_all.bat` / `start_all.sh` | backend · frontend · CLI（Java-only） | 本机非容器启动 |
| `start_java_mode.bat` | backend（java 模式） | 仅启动后端 |

> **重启宿主机后**：Docker Desktop 若已设置为随系统自启，容器会自动恢复，无需手动执行任何命令。  
> 如需手动恢复，执行与原来相同的启动命令即可。Cloudflare 隧道会自动重连，域名和回调 URL 无需重新配置。

### 企业微信 / 飞书接入额外需要

| 步骤 | 说明 |
|------|------|
| 在 Cloudflare Zero Trust 创建 Tunnel | 获取 token 后填入 `.env` 的 `CLOUDFLARE_TUNNEL_TOKEN` |
| 在 `.env.docker` 填写 IM 平台密钥 | 企业微信：`WECOM_*` 系列；飞书：`FEISHU_*` 系列 |
| 在 IM 平台后台配置回调 URL | `https://<你的域名>/wecom/callback` 或 `/feishu/callback` |
| 将服务器出口 IP 加入 IM 平台可信 IP | 发送消息走服务器真实 IP，不经 Cloudflare（`curl https://ipinfo.io/ip` 查询） |

---

## 目录

1. [启动速查](#启动速查)
2. [模块介绍](#模块介绍)
3. [快速上手](#快速上手)
4. [配置说明](#配置说明)
5. [核心功能详解](#核心功能详解)
6. [非功能性能力](#非功能性能力)
7. [项目结构](#项目结构)
8. [API 参考](#api-参考)
9. [最佳实践](#最佳实践)
10. [开发指南](#开发指南)
11. [常用运维命令](#常用运维命令)
12. [公网接入（Cloudflare Tunnel）](#公网接入cloudflare-tunnel)
13. [常见问题](#常见问题)

---

## 模块介绍

### Java 后端（`backend/web/`，唯一服务端）

> Python Agent 已于 2026-08-08 退役（commit `354bf33`）。
> 全部 AI 逻辑并入 Java 单后端：记忆/蒸馏/摘要/语义缓存、角色/会话/项目/任务领域服务、
> 知识/技能/分析/教学、任务调度、Channel 集成（飞书/企微/Telegram）、ComfyUI/MCP 集成。

**技术栈**：Java 21 · Spring Boot 3.5 · WebSocket · SSE · Reactor · JJWT · JDK HttpClient · Picocli（CLI）

**定位**：系统唯一服务端——JWT/WS 网关 + 全部 AI 逻辑（ReAct 编排、记忆、工具、角色/提示词、调度、IM 集成）。

**核心能力**：

| 能力 | 说明 |
|------|------|
| ReAct 推理循环 | `AgentOrchestrator` 构建上下文 → LLM 调用 → 工具执行（最多 5 轮）→ 流式输出 |
| 双模式工具调用 | 原生 Function Calling（qwen 等）+ Text-tool 文本解析（dolphin/phi2 等） |
| LLM 路由 | `LlmProviderRouter`：本地 Ollama 默认 + 云端 OpenAI 兼容 provider（按需在 `/admin/models` 激活） |
| 短期记忆 | 进程内双端队列，TTL 24h，最近 100 条 |
| 长期记忆 | `VectorMemoryRepository`（内存向量库），`EmbeddingService` 走 Ollama 真实 embedding（`nomic-embed-text`），失败回退 n-gram 哈希 |
| 自动记忆提炼 | 每 5 轮 LLM 提取事实（规则式兜底），每 10 轮阶段摘要 |
| 项目上下文 | 每 8 轮 LLM 提取项目 nuggets，注入 `[PROJECT CONTEXT]` |
| 语义缓存 | `SemanticResponseCache` 精确 + 语义相似命中，24h TTL |
| 任务调度 | `TaskSchedulerService`，支持 immediate / delay / interval / datetime / cron 五种类型，含 `llm_generate` 动作 |
| 角色/提示词/灵魂层 | `PromptService` + `SystemPromptBuilder` + `SoulLoader`（`soul/` 目录热加载）+ `heart_record` 工具 |
| 知识/技能/分析/教学 | `KnowledgeService` / `SkillService` / `AnalyticsService` / `TeachingService` 领域服务 |
| IM 渠道 | Feishu（WS 长连接 + OAuth）/ WeCom / Telegram 通道 + `ChannelRouter` 去重 + 限流重试 |
| 图片生成 | `ImageService` + ComfyUI（HTTP API，默认 txt2img 工作流）；SD WebUI / diffusers / SiliconFlow 未迁移（需求驱动再做） |
| 多模态输入 | 聊天图片 base64 全链路透传至 Ollama images 字段 |
| 消息撤回 | `ConversationService.retract` 级联删除短期记忆 + 长期检索排除 + 飞书官方撤回 |
| 分支失败检测 | `BranchFailureDetector` 6 信号（同工具同错误/连续重复/错误+空响应/铁律违反扫描等），命中即终止本轮 |
| CLI | Java CLI（`client/`）：login / chat / repl / model / persona / retract |

**内置工具**：计算器 · 时间查询 · 文件读取（白名单） · Web 搜索 · Shell（命令白名单） · MySQL 只读查询 · 飞书日历/任务 · 心证管理（heart_record）

---

### Java 后端（补充说明）

**定位**：上述能力全部内聚在 Java 单后端。`controller/` 层为薄路由，直接走本地领域服务
（Python 服务及其回滚路径已全部移除）。

**关键机制**：

| 能力 | 说明 |
|------|------|
| WebSocket 管理 | 维护所有前端 WS 连接，Session 级别隔离，ping/pong 保活 |
| 流式事件 | 本地 `ModelEvent` 流 → `thinking / chat_token / tool_calls_done / chat_done` |
| JWT 鉴权 | REST `JwtAuthFilter` + WS 握手 `JwtHandshakeInterceptor`，无效 token 直接 401 |
| 通知推送 | `TaskSchedulerService` 本地通知队列 → `notification` WS 事件广播到前端 |
| Channel Adapter 管理 | `ChannelAdapterManager` 管理 IM Channel 适配器注册 + `broadcast()` 并行广播；`FeishuChannelAdapter` 委托 `FeishuMessageSender` |
| 过载保护 | 线程池满时返回 503，不阻塞 Tomcat 线程 |

**WebSocket 消息协议**：

```
上行（前端 → Java）：chat_message | ping | get_system_info
下行（Java → 前端）：connection_established | thinking | chat_token |
                     tool_call_start | tool_calls_done | chat_done |
                     task_update | task_blocked | notification | error | pong
```

---

### Vue 前端 (`frontend/`)

**技术栈**：Vue 3 · Pinia · Vue Router 4 · Element Plus · Vite · PWA · marked · highlight.js

**定位**：响应式 SPA，全功能聊天界面，覆盖记忆、任务、项目等管理功能，支持桌面和移动端。

**页面**：

| 路由 | 功能 |
|------|------|
| `/chat` | 流式聊天，Markdown 渲染，工具进度卡片，历史会话侧边栏；config-bar 内嵌角色选择器 + 模型切换；支持图片附件/粘贴多模态输入；撤回模式可勾选/批量永久删除消息 |
| `/roles/editor` | 角色编辑器：六标签表单（基本信息/核心身份/用户画像/场景知识/限制条件/提示预览）|
| `/memory` | 短期/长期记忆查看，语义搜索（500ms 防抖），导入/导出/批量清空 |
| `/knowledge` | 知识库管理：拖拽上传、分块统计、文件列表（含描述/大小/创建时间）、删除 |
| `/project` | 项目列表 · Spec 编辑器 · 任务树（`[TASK_DONE]` 自动勾选） |
| `/image` | 图片生成：Prompt/风格预设/尺寸/步数/CFG 参数面板；Provider 状态徽章；生成结果 + 历史 Gallery |
| `/admin/skills` | Skill 管理：触发词路由、步骤定义、强制工具约束、启用/禁用、MD 导入 |
| `/admin/tasks` | 定时任务 CRUD，五种调度类型（immediate/delay/interval/datetime/cron） |
| `/admin/tools` | 工具列表（按分类过滤），点击查看参数说明 |
| `/admin/mcp` | MCP 配置：工具 API Key、推理参数（温度/最大 Token/Top-P）、系统资源上限调节 |
| `/admin/models` | 模型管理：当前激活模型、云端服务商 CRUD（OpenAI/DeepSeek/百炼等）、本地模型列表 |
| `/admin/logs` | 操作日志：用户/AI/工具/任务/错误按颜色分类的时间线，支持过滤 |
| `/admin/system` | CPU/RAM/GPU/磁盘/进程实时监控，资源用量可视化，跳转链接至配置页 |
| `/admin/stats` | 满意度 / 响应时间 / 工具调用排名统计 |

**关键体验**：
- 流式逐 token 渲染，`requestAnimationFrame` 节流滚动，完成后整体 Markdown 重渲染
- 取消流式：断开 WS 重连，Java SSE 随之中止
- 任务调度通知以 AI 气泡形式推入聊天，带跳转链接
- 聊天持久化（最近 50 条 localStorage）+ 会话历史（IndexedDB，最近 12 条）
- 深色模式持久化 · PWA 可安装到桌面

---

### CLI 客户端 (`client/`)

**技术栈**：Java 21 · Picocli · JDK HttpClient · SSE 解析

**定位**：连接 Java 后端（默认 `http://localhost:8080`）的命令行客户端；Python CLI 已于
2026-08-08 随 Agent 一起退役。

```bash
java -jar target/client-1.0-SNAPSHOT.jar login --username admin --password <pw>
java -jar target/client-1.0-SNAPSHOT.jar chat "你好，今天天气怎么样？"   # 单次问答（SSE 流式）
java -jar target/client-1.0-SNAPSHOT.jar chat "问题" --no-stream        # 等完整响应后输出
java -jar target/client-1.0-SNAPSHOT.jar repl                           # 交互式 REPL
```

**REPL 内置命令**：`!models` 列出模型 · `!model <name>` 切换模型 · `!personas` 列出角色 · `!persona <name>` 切换角色 · `!history` 查看历史（带编号） · `!retract <编号>` 按编号撤回消息 · `!sessions` 列出已保存会话 · `!clear` 清空会话 · `!exit` 退出

**认证**：`login` 通过 `/api/auth/cli-token` 换取 30 天 scoped token，保存到
`~/.intelligent-agent/token`（不保存 JWT_SECRET）。

---

## 快速上手

### 前置条件

| 软件 | 说明 |
|------|------|
| [Ollama](https://ollama.com) | 本地 LLM 推理引擎（必须） |
| Docker Desktop ≥ 4.x | 容器化部署（方式一必须） |
| Node.js ≥ 18 | 前端本地开发（方式二必须） |
| JDK 21 | 后端本地开发（方式二必须） |

**先拉取一个模型**：

```bash
ollama pull qwen2.5:7b      # 推荐：中文能力强，支持 Function Calling
ollama pull dolphin3:8b     # 无安全限制，适合创意/角色扮演场景
```

---

### 方式一：Docker 一键启动（推荐）

**1. 准备配置文件**

```bash
cp .env.docker.example .env.docker
```

编辑 `.env.docker`，**至少填写以下两项**（其余保留默认值即可）：

```env
JWT_SECRET=请替换为至少32字符的随机字符串abc123...
ADMIN_PASSWORD=你的管理员密码
```

**2. 启动**

```bash
# 标准启动（agent + backend + frontend）
docker compose up -d --build

# 含本地 Ollama + ComfyUI 容器（GPU 环境，本地图片生成需要这个）
docker compose --profile local up -d --build

# 含 HTTPS（需先生成证书）
sh nginx/generate-certs.sh
docker compose --profile https up -d --build
```

**3. 访问**

| 服务 | 地址 |
|------|------|
| Web 界面 | http://localhost:3000 |
| Java 后端 | http://localhost:8080 |

**代码更新后重建**：

```bash
docker compose pull                         # 拉取外部镜像最新版（ollama/nginx 等）
docker compose up -d --build                # 重建自定义镜像并重启
```

查看日志：

```bash
docker logs ia-backend -f --tail 50         # Java 后端日志
```

---

### 方式二：本地原生启动

**确保 Ollama 已运行**：`ollama serve`（并已拉取 `qwen2.5:7b`，可选 `nomic-embed-text`）

**Windows**（一键启动所有服务）：

```batch
start_all.bat
```

脚本会分别在独立窗口中启动 Backend / Frontend（Java-only）。

**Linux / macOS / WSL**：

```bash
./start_all.sh           # 后台启动三个服务，前台进入 CLI 客户端
./start_all.sh stop      # 停止所有服务（读取 .pids/ 目录）
./start_all.sh docker    # 等同于 docker compose up -d
./start_all.sh client    # 仅启动 CLI（服务已在运行时使用）
```

**手动逐步启动**（顺序：Ollama → Backend → Frontend）：

```bash
# 1. Java 后端（需在环境变量或根目录 .env 中提供 JWT_SECRET / ADMIN_PASSWORD）
cd backend/web && ./mvnw spring-boot:run        # Linux/macOS
cd backend\web && mvnw.cmd spring-boot:run      # Windows

# 2. Vue 前端（开发模式）
cd frontend && npm install && npm run dev       # http://localhost:5173
```

> 必须按 **Ollama → Backend → Frontend** 顺序启动（Java 后端自包含，不依赖外部 Python 服务）。

---

## 配置说明

### 环境变量（`.env.docker` 用于 Docker；根目录 `.env` 用于本地）

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `JWT_SECRET` | **必填** | ≥32 字符随机串，Backend 与 Client 使用同一密钥 |
| `ADMIN_PASSWORD` | **必填** | 管理后台密码 |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama 地址；Docker 内为 `http://ollama:11434` |
| `OLLAMA_MODEL` | `qwen2.5:7b` | 默认模型（可在 Web 界面运行时切换） |
| `OLLAMA_NUM_GPU` | `-1`（自动） | GPU 层数；显存不足时手动指定，如 `18` |
| `OLLAMA_TIMEOUT` | `600s` | LLM 推理超时（CPU 跑 7B 约需 60-120s） |
| `MAX_CONTEXT_TOKENS` | `8000` | 发送给 LLM 的上下文 token 预算（配合 `OLLAMA_NUM_CTX`） |
| `LLM_EXTRACTION_ENABLED` | `true` | 记忆蒸馏 / 项目上下文 LLM 提取开关（TODO-110 Task 5） |
| `EMBEDDING_ENABLED` | `true` | 真实 embedding 开关（Ollama `/api/embed`，失败回退 n-gram） |
| `EMBEDDING_MODEL` | `nomic-embed-text` | 嵌入模型名（768 维） |
| `PROJECT_EXTRACTION_INTERVAL` | `8` | 项目上下文 LLM 提取轮次间隔 |
| `CLOUD_PROVIDER` | 空 | 云端 LLM fallback（`dashscope` / `deepseek` / `zhipu` / `moonshot` 等） |
| `CLOUD_API_KEY` | 空 | 云端 LLM API Key |
| `CORS_ALLOWED_ORIGINS` | `*` | 生产环境应改为具体域名 |
| `LOG_LEVEL` | `WARNING` | 日志级别（`DEBUG` 用于开发调试） |
| `FEISHU_OAUTH_REDIRECT_URI` | 空 | 飞书 OAuth 公网 callback URL（Cloudflare Tunnel 等）|

> 说明：蒸馏间隔（5 轮）/ 摘要间隔（10 轮）/ 会话消息上限（200）为代码内固定值；
> 并发上限与缓存大小在 `/admin/mcp` 运行时调节（持久化到 `backend/web/data/runtime_config.json`）；
> 云端 API Key 与飞书 user_token 落盘加密由 `SecretCrypto` 处理（密钥由 `JWT_SECRET` 派生，无需额外环境变量）。

### 客户端配置

Java CLI 无需配置文件：`login` 时从后端换取 scoped token 并保存到
`~/.intelligent-agent/token`；默认后端地址 `http://localhost:8080`。

### 运行时调节（无需重启）

Web 界面 → **MCP 配置页**（`/admin/mcp`）可在线调节温度、最大 Token、Top-P 等推理参数，以及并发上限、缓存条目数、记忆大小等系统资源参数，均写入 `backend/web/data/runtime_config.json`，重启后自动恢复。

---

## 核心功能详解

### ReAct 多工具推理

```
用户消息
    │
    ▼  AgentOrchestrator.initialMessages()
    │  注入：短期记忆 + 长期语义检索 + 项目上下文 + 任务列表 + Spec（每10轮）
    │
    ▼  LlmProviderRouter.complete()   ← 第一次 LLM 调用
    │
    ├── 有工具调用 ──► ToolExecutor.execute() ──► 追加结果 ──► 循环（最多 5 轮）
    │
    └── 无工具调用 ──► stream()  ← SSE/WS 流式输出
```

对不支持 Function Calling 的模型（dolphin / phi2 等），自动切换到文本解析模式，支持 JSON / `<tool_call>` 标签 / Markdown 代码块 / 纯文本四种格式。

### 两级记忆系统

```
对话 ──每5轮──► MemoryDistillationService ──LLM提炼──► MemoryRepository（内存向量库）
    │                                         facts / preferences / summaries
    │  每10轮
    ├──────────► summarize() ────────────────► MemoryRepository (type=summary)
    │
    │  每次聊天（有 project_id 时）
    └──每8轮──► 项目上下文提取（LLM）──────► MemoryRepository（type=project）
                      │
                      └── 每次 loadContext() 语义检索注入 [PROJECT CONTEXT]
```

### 任务调度系统

在任务管理页创建定时任务，支持五种调度类型：

| 类型 | 示例 | 说明 |
|------|------|------|
| `immediate` | — | 立即执行一次 |
| `delay` | 60 秒后执行 | 延迟 N 秒后执行一次 |
| `interval` | 每 3600 秒 | 按固定间隔循环执行 |
| `datetime` | 2026-12-31 08:00 | 指定时间点执行一次 |
| `cron` | `0 8 * * *` | Cron 表达式（每天 8:00） |

动作类型：`log`（固定文字通知）/ `llm_generate`（LLM 生成内容推送到聊天）/ 任意工具名

### 项目系统（三大智能能力）

**1. 规格驱动开发**：在项目视图写入 Markdown 规格文档，每 5 轮以 `[SPEC]` 系统消息注入 LLM，强制回顾原始需求，避免偏离。

**2. 上下文持久化**：每 8 轮从对话中提取关键决策/约束写入 ChromaDB，每次聊天语义检索注入 `[PROJECT CONTEXT]`，长对话不遗忘核心信息。

**3. 自主任务分解**：LLM 将目标拆解为树形任务，回复中写 `[TASK_DONE:<id>]` 时前端自动勾选对应任务。

### 角色（Persona）系统

角色数据由 `RoleService` 管理（`backend/web/data/roles.json`），支持运行时 CRUD，无需重启。

内置角色：默认助手 · 创意写手 · 技术专家

---

## 非功能性能力

> 功能之外，系统在性能、可靠性、可观测性、安全性、可测试性、离线体验六个维度上做了哪些工程化建设。

### 性能与可伸缩性

| 能力 | 实现方式 |
|------|----------|
| 精确语义缓存 | `SemanticResponseCache` persona/model 感知 key 精确命中，24h TTL |
| 语义相似命中 | 余弦相似度 ≥ 0.8 时直接返回历史响应（`EmbeddingService` 真实 embedding / n-gram 兜底） |
| 流式输出 | SSE 逐 token 推送，前端 `requestAnimationFrame` 节流渲染，完成后整体重渲染 Markdown，避免逐 token 重排版的性能损耗 |
| 前端代码分割 | 路由级懒加载（`() => import('@/views/XxxView.vue')`），首屏仅加载聊天页所需代码 |
| 上下文 token 预算 | `MAX_CONTEXT_TOKENS=8000` 控制发送给 LLM 的上下文长度（配合 `OLLAMA_NUM_CTX=8192`）；`SoulLoader.max_total_chars=14000` 告警阈值，超过时提示 token 预算风险 |

### 可靠性与容错

| 能力 | 实现方式 |
|------|----------|
| 超时保护 | LLM / 提取 / embedding 调用均带超时，失败回退规则式逻辑或错误事件，不阻塞主链路 |
| 云端路由 | `LlmProviderRouter` 按请求模型路由到已配置的云端 provider（非失败自动切换） |
| 网关过载保护 | Java 网关线程池满时直接返回 503，不阻塞 Tomcat 工作线程，避免雪崩 |
| WebSocket 自动重连 | 前端检测连接断开后自动重连，并在重连成功且模型/角色列表为空时补拉一次（规避容器重启时序问题） |
| 分支失败检测 | `BranchFailureDetector` 6 信号（同工具同错误/连续重复/错误+空响应/铁律违反扫描等），命中即终止本轮并给出失败说明 |
| 工具错误分级重试 | 鉴权错（401/403）重试 1 次，系统错（5xx/超时）重试 3 次，避免瞬时故障导致对话中断 |
| 容器健康探针 | `docker-compose.yml` 为 backend / frontend 均配置 `healthcheck`，编排时按依赖顺序等待健康 |
| 失职自查钩子 | 关键操作前后自动验证：飞书推送前后检查内容非空+message_id 有效、scheduler 任务执行后确认输出文件存在、heart_record 写入后读回确认内容正确（TODO-93） |
| 长期记忆召回 | 按用户/角色/项目过滤 + 语义相似度排序，撤回内容加入排除集不再命中 |

### 可观测性

| 能力 | 实现方式 |
|------|----------|
| 健康检查 | `GET /api/health`（Java 后端），供容器探针和负载均衡器探测 |
| 运营统计 | `/api/analytics/*` + `/admin/stats`（满意度、响应时间分布、工具调用统计） |
| Channel 健康端点 | `GET /health/channels` 返回各 IM channel 的 ChannelMetric（成功率/平均延迟/限流拒绝次数），用于生产监控 |
| 实时系统监控面板 | `/admin/system` 页面展示 CPU / 内存 / GPU / 磁盘占用与进程排行 |
| 运营统计面板 | `/admin/stats` 页面展示满意度、响应时间分布、工具调用排名 |
| 分级日志 | `LOG_LEVEL` 环境变量控制（`DEBUG`/`INFO`/`WARNING`），Java 后端统一配置 |

### 安全

| 能力 | 实现方式 |
|------|----------|
| JWT 鉴权 | Token 24h 有效、滑动续期；WebSocket 握手阶段校验，无效 token 直接拒绝 |
| 用户级隔离 | 模型切换、角色切换、记忆、任务等均按 `user_id` 维度隔离（per-user provider/persona 映射），用户 ID 经网关透传到 Agent |
| CORS 白名单 | `CORS_ALLOWED_ORIGINS` 可配置，生产环境应收紧为具体域名 |
| TLS / HTTPS | 提供 Nginx 配置模板 + 证书生成脚本（`nginx/generate-certs.sh`），`docker compose --profile https` 一键启用 |
| 安全的二次确认交互 | 危险操作（删除任务、清空对话等）使用自研 `ConfirmDialog`（纯前端组件渲染），刻意不用 `window.confirm`/`alert`——后者在 PWA / WebView / 浏览器"阻止弹窗"场景下会被静默拦截、按钮看起来毫无反应 |

### 可测试性

| 层 | 测试框架 | 覆盖范围 |
|------|------|------|
| Java 后端测试 | `mvnw test`（~270 个） | ReAct/分支检测、LLM provider 契约、记忆/蒸馏/缓存、角色/会话/项目/任务领域、工具、调度、IM 通道、迁移校验、E2E 契约（MockMvc）等 |
| Backend 单元测试 | JUnit 5 | WebSocket 消息序列化、JWT 工具类、JSON 工具类 |
| Frontend 单元测试 | Vitest | JWT 处理逻辑等关键工具函数 |
| E2E 端到端测试 | JUnit + JDK HttpClient（tests/e2e-java） | 从客户端发起 HTTP 请求打通 Java:8080 全链路，覆盖认证/聊天/记忆/任务/项目/角色/Skill/云端/通知/消息撤回 |

### 离线与移动端体验

| 能力 | 实现方式 |
|------|----------|
| PWA 可安装 | 支持"添加到主屏幕"，独立窗口运行 |
| 离线缓存策略（`sw.js`，缓存版本 `ia-pwa-v2`）| 带哈希的 JS/CSS → cache-first；HTML/manifest → network-first（保证拿到最新外壳）；图标/字体等 → stale-while-revalidate |
| 响应式布局 | 移动端汉堡菜单、单列卡片布局、弹窗满屏适配 |
| 深色模式 | 跟随系统或手动切换，状态持久化 |

---

## 项目结构

```
intelligent_agent/
├── backend/web/                    Java Spring Boot 单后端（唯一服务端）
│   └── src/main/java/com/intelligent/agent/web/
│       ├── ai/agent/               AgentOrchestrator（ReAct）+ 分支失败检测 + 任务标记
│       ├── ai/llm/                 Ollama / 云端 OpenAI 兼容 provider + 路由
│       ├── ai/tool/                ToolExecutor + 内置工具（9 个）+ 文本工具解析
│       ├── ai/memory/              短期记忆 / 蒸馏 / 摘要 / 语义缓存 / 项目上下文
│       ├── ai/prompt/              灵魂层加载 + SystemPromptBuilder + PromptService
│       ├── domain/                 角色 / 会话 / 项目 / 任务 / 知识 / 技能 / 分析 / 教学
│       ├── infrastructure/         向量仓库 / 调度器 / 迁移 / 观测 / 文件存储
│       ├── integration/            Feishu / WeCom / Telegram 通道 + ComfyUI / MCP
│       ├── controller/             薄路由（java 模式走本地领域服务）
│       └── service/                AgentService / ImageService / ConfigRuntimeService 等
│
├── frontend/                       Vue 3 SPA
│   └── src/
│       ├── views/                  页面组件（Chat/RoleEditor/Memory/Project/Tasks/Tools/Skills/MCP/Models/System/Stats/Log）
│       ├── stores/                 Pinia 状态（WebSocket/Auth/LocalSession/Project/ConfirmDialog）
│       ├── config/routes.config.js 路由导航单一来源（侧边栏/Header 均从此读取）
│       └── services/               WebSocket 客户端 + REST 封装（api.js/localDB.js）
│
├── client/                         Java CLI 客户端（Java 21 + Picocli，连接 backend:8080）
│   ├── Main.java                   Picocli 入口（login / chat / repl / model / persona / retract）
│   ├── BackendClient.java          HTTP + SSE 客户端
│   ├── ReplCommand.java            交互式 REPL
│   └── SessionStore.java           会话持久化（JSON 文件）
│
├── tests/e2e-java/                 端到端测试套件（JUnit + JDK HttpClient，仅测 Java 后端）
│   ├── ApiClient.java              公共 HTTP 客户端：探活、JWT 登录、REST 请求
│   ├── E2EBaseTest.java            基类：后端不可达整类跳过
│   ├── AuthE2ETest.java            认证（登录/登出/无 token 鉴权）
│   ├── HealthE2ETest.java          服务健康检测
│   ├── ChatE2ETest.java            3 个聊天维度（云端/本地/dolphin 无限制）
│   ├── MemoryE2ETest.java          记忆增删改查、语义搜索、导出、提炼
│   ├── TasksE2ETest.java           调度任务 CRUD + 取消
│   ├── test_projects.py            项目 CRUD + Spec + 任务列表
│   ├── test_roles.py               角色列表/激活/停用
│   ├── test_skills.py              Skill CRUD + toggle
│   ├── test_cloud.py               云端服务商 CRUD + 激活/停用
│   ├── test_models.py              模型列表/切换
│   ├── test_conversations.py       历史会话
│   ├── test_analytics.py           统计/反馈
│   ├── test_notifications.py       通知轮询
│   ├── test_tools.py               工具列表
│   └── test_config.py              运行时配置读写
│
├── soul/                           Soul 层（身份/灵魂/心跳/心证铁卷/主人铁律/私密档案）
│   ├── SOUL.md / IDENTITY.md       核心身份定义
│   ├── HEARTBEAT.md                能力边界自检铁律
│   ├── heart.md                    心证铁卷（用户显式永久记忆）
│   ├── rules.md                    主人铁律（21 条不可违反规则，7 作用分类）
│   ├── USER.md / MEMORY.md         用户画像 / 自维护记忆
│   └── whisper.md                  私密档案（不上 IM 渠道）
├── nginx/                          HTTPS Nginx 配置 + 证书生成脚本
├── docker-compose.yml              容器编排（local/https/tunnel 三个 profile）
├── .env.docker.example             Docker 环境变量模板
├── start_all.sh                    统一启动脚本（Linux/macOS/WSL）
├── start_all.bat                   统一启动脚本（Windows）
└── CLAUDE.md                       Claude Code 项目指令
```

---

## API 参考

所有接口由 Java 后端提供（port 8080，Java-only；Python Agent 已于 2026-08-08 退役）。

### 聊天

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 非流式聊天（REST 直调）|
| POST | `/api/chat/stream` | SSE 流式聊天（Java 消费）|

**请求体**：

```json
{
  "message": "用户消息",
  "image_base64": "base64编码的图片（可选，多模态输入）",
  "use_tools": true,
  "use_memory": true,
  "project_id": "proj-uuid",
  "pending_tasks": [
    { "id": "task-xxx", "title": "实现登录", "status": "pending", "subtasks": [] }
  ]
}
```

**SSE 事件类型**：

| type | 含义 |
|------|------|
| `thinking` | 开始推理 |
| `chat_token` | 流式 token |
| `tool_call_start` | 工具开始执行 |
| `tool_calls_done` | 本轮工具全部完成 |
| `chat_done` | 本轮完整回复 |
| `task_update` | 检测到 `[TASK_DONE:<id>]`，含 task_id |
| `task_blocked` | 检测到 `[TASK_BLOCKED:<id>]`，含 task_id |
| `error` | 异常 |

### 常用接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查 |
| GET | `/api/models` | 列出可用模型 |
| POST | `/api/model/switch` | 切换模型（per-user）|
| GET | `/api/personas` | 列出角色 |
| POST | `/api/personas/switch` | 切换角色（per-user）|
| GET | `/api/memory/list` | 列出记忆条目 |
| GET | `/api/memory/search?q=关键词` | 语义搜索 |
| GET | `/api/memory/export?format=json` | 导出记忆 |
| GET | `/api/tasks/list` | 列出调度任务 |
| POST | `/api/tasks/create` | 创建调度任务 |
| PUT | `/api/project/spec` | 写入项目规格文档 |
| POST | `/api/project/tasks/decompose` | AI 任务分解 |
| GET | `/api/tools/list` | 列出注册工具 |
| GET | `/api/cloud/providers` | 列出云端服务商配置 |
| POST | `/api/cloud/providers` | 新建云端服务商配置 |
| POST | `/api/cloud/providers/{id}/activate` | 激活指定服务商（切换全局 provider）|
| POST | `/api/cloud/deactivate` | 停用云端，切回 Ollama |
| GET | `/api/feishu/oauth/authorize?open_id=xxx` | 获取飞书 OAuth 授权 URL（需 JWT）|
| GET | `/api/feishu/oauth/callback` | OAuth 回调，返回 HTML（无 JWT，由飞书服务器重定向）|
| GET | `/api/feishu/oauth/status?open_id=xxx` | 查询 OAuth 授权状态（需 JWT）|

### 知识库

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/knowledge/upload` | 上传文件（.txt/.md/.pdf/.json，≤10MB），自动段落/句子边界分块写入向量记忆 |
| GET | `/api/knowledge/files` | 列出当前用户已入库文件（含文件名/分块数/大小/创建时间）|
| DELETE | `/api/knowledge/files/{file_id}` | 删除文件及其所有向量块 |

### 历史会话

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/conversations` | 列出用户所有会话（元数据 + 首条消息预览，最多 100 条）|
| GET | `/api/conversations/{session_id}` | 获取某会话完整消息列表 |
| DELETE | `/api/conversations/{session_id}` | 删除指定会话 |
| DELETE | `/api/conversations` | 清空用户所有会话 |
| POST | `/api/conversations/branch` | 从指定消息列表创建分支会话 |
| POST | `/api/conversations/{session_id}/retract` | 按 message_id 撤回（永久删除）消息，级联清理短期记忆，单次最多 50 条 |

---

## 最佳实践

### 首次部署清单（从零到可验证可用）

按顺序完成以下步骤，每步都有明确的"验证通过"标志，出问题可精确定位到对应环节：

1. **拉取模型**：`ollama pull qwen2.5:7b`
   验证：`ollama list` 能看到该模型
2. **准备配置**：`cp .env.docker.example .env.docker`，填写 `JWT_SECRET`（≥32 字符随机串）和 `ADMIN_PASSWORD`
   验证：`grep -c "请替换\|your_password" .env.docker` 返回 `0`（即占位符已全部替换）
3. **启动容器**：`docker compose up -d --build`
   验证：`docker compose ps` 两个服务（backend/frontend）状态均为 `healthy`
4. **检查后端健康**：`curl http://localhost:8080/api/health`
   验证：返回 JSON 中 `status` 字段为 `UP`
5. **登录验证**：浏览器打开 `http://localhost:3000`，用 `admin` / 你设置的 `ADMIN_PASSWORD` 登录
   验证：登录后能看到聊天界面，右上角显示模型/角色下拉框且非空（若为空见 [常见问题](#常见问题)）
6. **端到端对话验证**：发送一条消息，确认能收到流式回复
   验证：消息气泡逐字出现，最终完整渲染 Markdown，无报错提示
7. **任务调度验证**：在 `/admin/tasks` 创建一个 `delay 60秒` 的测试任务，等待执行
   验证：60 秒后任务状态变为"已完成"，且聊天页收到对应通知气泡

> 全部 7 步通过即表示系统已就绪。任一步骤失败时，先看对应容器日志（`docker logs ia-<服务名> -f --tail 100`）再排查配置。

### 模型选择

| 场景 | 推荐模型 | 说明 |
|------|----------|------|
| 日常对话 + 工具调用 | `qwen2.5:7b` | 中英文均衡，Function Calling 准确 |
| 代码 / 技术问答 | `deepseek-coder:7b` | 代码生成强 |
| 创意 / 无限制场景 | `dolphin3:8b` | 配合 dolphin persona 使用 |
| 低资源设备（4GB RAM） | `qwen2.5:3b` | 轻量可运行 |

> **GPU 内存不足**：设置 `OLLAMA_NUM_GPU=<层数>`，如 6GB 显存 + 16GB 模型设为 `18`，其余层 CPU 推理。

### 项目模式

1. **先写规格再对话**：目标、约束、不做的事写得越清晰，AI 回顾越有效
2. **任务粒度适中**：建议控制在「1-2 轮对话可完成」的粒度，过大的任务手动拆分
3. **不同功能用独立项目**：避免上下文和任务列表相互干扰
4. **主动触发上下文提取**：关键决策后调用 `POST /api/project/context/extract` 立即保存

### 记忆管理

- **定期清理**：Memory 面板删除过时条目，或导出 JSON 后清空重来
- **跨设备迁移**：Memory 面板导出/导入（迁移包含长期记忆与业务 JSON，恢复后完整可用）

### 性能调优

- 并发上限：`/admin/mcp` 系统资源配置中调 `inference_concurrency`（CPU 跑大模型建议 1）
- `OLLAMA_TIMEOUT`：CPU 7B 约 60-120s，16B 约 200s，按实际硬件调整
- `MAX_CONTEXT_TOKENS`：CPU 推理建议 ≤ 8192，显存充足可放到 16384

### 安全

- `JWT_SECRET` 必须 ≥ 32 字符的强随机串，不能使用默认值
- `CORS_ALLOWED_ORIGINS` 生产环境改为具体域名，不保留 `*`
- 公网部署前必须配置 TLS：`sh nginx/generate-certs.sh && docker compose --profile https up -d`
- Ollama 端口 11434 仅允许内网访问，不要对公网暴露

---

## 开发指南

### 新增工具

1. 在 `backend/web/.../ai/tool/builtin/` 下新建工具类，实现 `AgentTool` 接口
2. 在 `AgentConfig` 中注册为 Spring Bean（参考 `CalculatorTool` / `WebSearchTool`）
3. 工具自动进入 `ToolExecutor`，对 LLM 可见，同时自动注册为调度器可用动作

### 新增角色

在前端 Web 界面 `/roles/editor` 创建角色（推荐，表单化），或直接调用 `POST /api/roles`（JSON body）。角色持久化到 `backend/web/data/`，无需重启。

### 开发命令速查

```bash
# Java Backend
cd backend/web
./mvnw spring-boot:run
./mvnw test
./mvnw package

# E2E（需 backend + Ollama 运行）
cd backend/web && ./mvnw.cmd -f ../../tests/e2e-java/pom.xml test

# Frontend
cd frontend
npm install
npm run dev      # http://localhost:5173
npm run build
```

---

## 常用运维命令

```bash
# ── Docker ──────────────────────────────────────────────────────
docker compose ps                               # 容器状态
docker logs ia-backend -f --tail 50             # Java 后端实时日志
docker compose restart ia-backend               # 重启 Java 后端

# 前端热更新（不重建镜像，约 10s）
cd frontend && npm run build
docker cp frontend/dist ia-frontend:/usr/share/nginx/html_new
docker exec ia-frontend sh -c \
  "rm -rf /html_old && mv /usr/share/nginx/html /html_old && \
   mv /usr/share/nginx/html_new /usr/share/nginx/html && nginx -s reload"

docker compose down                             # 停止并删除容器（数据卷保留）
docker compose down -v                          # 同上 + 删除数据卷（慎用）

# ── 本地原生 ──────────────────────────────────────────────────────
./start_all.sh stop                             # 停止所有本地服务
```

---

## 公网接入（Cloudflare Tunnel）

当前系统通过 **Cloudflare Tunnel** 对外暴露服务，无需公网 IP 和端口映射。

### 工作原理

```
企业微信 / 飞书回调 / 外部浏览器
        │  HTTPS  intelligent.eu.cc
        ▼
Cloudflare 边缘节点（全球 CDN，中国可访问）
        │  Cloudflare Tunnel（ia-cloudflared 容器主动建立加密长连接）
        ▼
ia-cloudflared 容器
        │  Docker 内网 HTTP
        ▼
ia-backend:8080（Java 单后端，全部 AI 逻辑）
```

`ia-cloudflared` 在启动时主动向 Cloudflare 建立隧道，域名 `intelligent.eu.cc` 的 DNS 指向该隧道，**不绑定宿主机 IP**。启动命令：

```bash
# 仅公网隧道（云端 LLM，不需要本地 Ollama）
docker compose --profile tunnel up -d --build

# 本地 Ollama 推理 + 公网隧道（两个 profile 同时指定）
docker compose --profile local --profile tunnel up -d --build

# 查看隧道连接状态
docker logs ia-cloudflared --tail 20
```

> **profile 速查**：`local` = ollama + comfyui；`tunnel` = cloudflared + ngrok（二选一，当前用 cloudflared）；两者互不包含，按需组合。

---

### 服务重启的影响

| 重启操作 | 影响 | 说明 |
|----------|------|------|
| `docker compose restart ia-backend` | 约 5-30s 不可用 | cloudflared 保持运行，backend 重启期间请求到达后无法转发 |
| `docker compose restart ia-cloudflared` | 通常 <5s 短暂断连 | 重连 Cloudflare 极快，DNS 无任何变化 |
| 宿主机重启 | 取决于 Docker Desktop 自启设置 | Docker Desktop 若随系统自启，`restart: unless-stopped` 会自动拉起全部容器，域名通常 1-2 分钟内恢复 |
| 更换 tunnel token | 需手动重启 cloudflared | `docker compose restart ia-cloudflared` |

> **关键结论**：企业微信 / 飞书的回调 URL（`https://intelligent.eu.cc/wecom/callback` 等）在重启后**无需重新配置**，域名绑定不变。

---

### 迁移到新服务器

如需将整套系统迁移到另一台机器，按以下顺序操作：

**第一步：在旧机器上导出数据卷**

需要迁移的卷（按重要性排序）：

| 卷名 | 内容 | 是否必须迁移 |
|------|------|------------|
| `agent_data` | 对话历史、任务、角色偏好、云端服务商配置、运行时参数 | **必须** |
| `agent_chroma_data` | 短期记忆向量库（ChromaDB） | **必须**（否则记忆清零） |
| `agent_chroma_data_longterm` | 长期记忆向量库（ChromaDB） | **必须**（否则记忆清零） |
| `agent_cache` | HuggingFace embedding 模型缓存（`all-MiniLM-L6-v2`） | **必须**（`HF_HUB_OFFLINE=1` 下无此卷 Agent 无法启动） |
| `ollama_models` | Ollama 本地推理模型（体积大，数 GB） | 可选：不迁移则在新机器重新 `ollama pull` |

```bash
# 必须迁移的四个卷
docker run --rm -v agent_data:/data -v $(pwd):/backup alpine \
  tar czf /backup/agent_data.tar.gz /data
docker run --rm -v agent_chroma_data:/data -v $(pwd):/backup alpine \
  tar czf /backup/agent_chroma_data.tar.gz /data
docker run --rm -v agent_chroma_data_longterm:/data -v $(pwd):/backup alpine \
  tar czf /backup/agent_chroma_data_longterm.tar.gz /data
docker run --rm -v agent_cache:/data -v $(pwd):/backup alpine \
  tar czf /backup/agent_cache.tar.gz /data

# 可选：ollama 模型（体积大，也可到新机器重新 pull）
docker run --rm -v ollama_models:/data -v $(pwd):/backup alpine \
  tar czf /backup/ollama_models.tar.gz /data
```

**第二步：在新机器上准备代码和配置**

```bash
git clone <repo-url>

# 手动拷贝（不在 git 里，含 tunnel token / 密钥等）：
# .env.docker    ← 所有运行时密钥
# .env           ← compose 变量（含 CLOUDFLARE_TUNNEL_TOKEN）

# 恢复数据卷
docker run --rm -v agent_data:/data -v $(pwd):/backup alpine \
  tar xzf /backup/agent_data.tar.gz -C /
docker run --rm -v agent_chroma_data:/data -v $(pwd):/backup alpine \
  tar xzf /backup/agent_chroma_data.tar.gz -C /
docker run --rm -v agent_chroma_data_longterm:/data -v $(pwd):/backup alpine \
  tar xzf /backup/agent_chroma_data_longterm.tar.gz -C /
docker run --rm -v agent_cache:/data -v $(pwd):/backup alpine \
  tar xzf /backup/agent_cache.tar.gz -C /
```

**第三步：启动**

```bash
docker compose --profile tunnel up -d --build
```

Cloudflare Tunnel token **不绑定机器**。新机器用同一个 token 启动 `ia-cloudflared` 后，隧道自动切换过来，域名 `intelligent.eu.cc` 无需任何 DNS 改动。

**第四步：更新 IM 平台可信 IP（如有）**

企业微信/飞书等平台收到回调时走 Cloudflare Tunnel（入站），但 Java 后端**主动调用** IM 平台 API 发送消息时（出站），走的是新服务器的真实出口 IP，不经 Cloudflare。

```bash
# 查新机器出口 IP
curl https://ipinfo.io/ip
```

然后去各平台后台更新 IP 白名单：
- **企业微信**：应用管理 → 选应用 → **企业可信 IP** → 添加新 IP
- **飞书**：若有出站 IP 白名单配置，同样更新

回调 URL、Token、AES Key 等均不需要改动。

---

## 常见问题

**Q: Backend 容器 unhealthy 无法启动**  
A: `docker logs ia-backend` 查看详情。最常见：`.env.docker` 未配置或 `JWT_SECRET` 为空。

**Q: CPU 推理很慢**  
A: CPU 跑 7B 模型约 60-120s 属正常。到 `/admin/mcp` 把 `inference_concurrency` 设为 1，防止多请求争抢 CPU。

**Q: dolphin 模型工具调用不工作**  
A: dolphin 不支持原生 Function Calling，系统自动切换 Text-tool 解析模式，正常使用即可。

**Q: 数据存在哪里**  
A: Docker 模式：`agent_data` 命名卷（`agent/data/`）和 `agent/chroma-data/`；本地模式：`agent/data/` 和 `agent/chroma_data/`。

**Q: 如何接入云端 LLM（网络不好时 fallback）**  
A: 推荐在 `/admin/models` 模型管理页添加云端服务商配置（支持 OpenAI / DeepSeek / 阿里百炼等 7 家，填 BaseURL + API Key + 模型名），点击"激活"立即切换，配置持久化到 `agent/data/cloud_providers.json`，重启后自动恢复。也可在 `.env.docker` 设置 `CLOUD_PROVIDER` 和 `CLOUD_API_KEY` 作为启动默认值。

---

## License

MIT

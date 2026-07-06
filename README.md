# Intelligent Agent System

> 本地优先的三层 AI 智能体平台：Ollama 本地推理 · Spring Boot WebSocket 网关 · Vue 3 聊天界面 · Python CLI 客户端  
> 支持多工具调用、长期记忆、任务调度、多角色切换、项目上下文持久化。
> 最后更新：2026-07-05（W1-W6 heart-record plan 全部落地：心证层/分支保护/缓存/自查/进度恢复/跨session记忆增强）

```
浏览器 / CLI 客户端
        │  WebSocket (流式) + REST
        ▼
┌─────────────────────────────────┐
│  Java 后端  (Spring Boot :8080) │  ← 纯网关：JWT 鉴权、WS 管理、代理转发
└────────────────┬────────────────┘
                 │  HTTP + SSE
                 ▼
┌─────────────────────────────────┐
│  Python Agent  (FastAPI  :8000) │  ← 全部 AI 逻辑在此
│  ┌──────────┐  ┌─────────────┐  │
│  │ 记忆系统 │  │  工具管理   │  │
│  │ 任务调度 │  │  角色系统   │  │
│  │ 项目系统 │  │  技能路由   │  │
│  └──────────┘  └─────────────┘  │
└──────────┬──────────────────────┘
           │
     ┌─────┴──────┐
     ▼            ▼
  Ollama       ChromaDB
 (:11434)    (进程内嵌入)
 本地 LLM     向量长期记忆
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
| `docker compose up -d --build` | agent · backend · frontend | 纯本地使用，云端 LLM 或不需要公网 |
| `docker compose --profile tunnel up -d --build` | agent · backend · frontend · **cloudflared** | 需要公网访问（企业微信/飞书回调），使用云端 LLM |
| `docker compose --profile local up -d --build` | agent · backend · frontend · **ollama · comfyui** | 本地 GPU 推理，无需公网 |
| `docker compose --profile local --profile tunnel up -d --build` | 全部服务 | 本地 GPU 推理 + 公网隧道 |

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

### Python Agent (`agent/`)

**技术栈**：Python 3.10 · FastAPI · Uvicorn · ChromaDB · sentence-transformers · Ollama SDK

**定位**：系统的 AI 大脑，所有智能逻辑的唯一执行地，Java 后端不含任何 AI 代码。

**核心能力**：

| 能力 | 说明 |
|------|------|
| ReAct 推理循环 | 构建上下文 → LLM 调用 → 工具执行（最多 5 轮）→ 流式输出 |
| 双模式工具调用 | 原生 Function Calling（qwen/llama 等）+ Text-tool 文本解析（dolphin/phi2 等） |
| 短期记忆 | 进程内双端队列，TTL 24h，最近 100 条，对话上下文复用 |
| 长期记忆 | ChromaDB 向量库（all-MiniLM-L6-v2 嵌入），语义检索 |
| 自动记忆提炼 | 每 5 轮对话自动提炼事实写入长期记忆，每 10 轮生成阶段摘要 |
| 语义缓存 | L2 余弦相似度 ≥ 0.92 时直接返回缓存响应，减少 LLM 调用 |
| 任务调度 | 后台线程，支持 immediate / delay / interval / datetime / cron 五种调度类型 |
| 角色系统 | `personas/*.md` 热加载，新增角色无需重启 |
| 项目系统 | 每个项目含规格文档（Spec）+ 任务树，LLM 回复中 `[TASK_DONE]` 自动更新状态 |
| 云端 Fallback | Ollama 不可用时自动切换到 DashScope / DeepSeek / ZhipuAI / Moonshot 等 |
| 图片生成 | ComfyUI（默认）/ SD WebUI / diffusers 进程内推理 / SiliconFlow 云端四种 Provider |
| 知识库 | 上传 .txt/.md/.pdf/.json 文件，段落/句子边界分块，ChromaDB 向量索引，聊天时自动语义检索注入上下文 |
| 多模态输入 | 聊天输入区支持图片附件/粘贴，base64 全链路透传至 Ollama images 字段（llava / qwen-vl 等） |
| 消息撤回 | 用户可手动撤回任意历史消息（user/assistant），从对话 JSON + 短期记忆中真正删除，避免错误回复污染后续上下文；蒸馏来源标记排除检索，飞书消息联动官方撤回 API |
| 心证铁卷 | `soul/heart.md` 用户显式永久记忆，优先级高于自动蒸馏的长期记忆；`heart_record` 工具支持 LLM 在对话中 append/list/delete 心证条目，写入前自动轮转备份 |
| 分支失败自动撤回 | 5 信号实时检测 ReAct 推理失败螺旋（同工具同错误/连续重复输出/用户纠偏/空响应+异常/重试耗尽），命中即自动撤回最近 2 轮 + 注入 `[BRANCH_RESET]` 重新推理，每会话最多触发 1 次 |
| 进度恢复协议 | 新会话启动时自动扫描 `memory/work/` 目录下的 `progress_state.md`，检测未完成任务并注入 `[PROGRESS RECOVERY]` 上下文；用户说"继续上次的"即可无缝恢复 |
| 跨 session 记忆增强 | 蒸馏时自动识别任务进度关键词（`[TASK_DONE]`/`[TASK_BLOCKED]` 等）并打 `task_progress` 标签；进度恢复时额外查询跨 session 的 LTM 进度记忆，注入 `[TASK PROGRESS MEMORY]` |

**内置工具**：计算器 · 时间查询 · 文件读写 · DuckDuckGo 搜索 · Shell 命令 · MySQL 查询 · 图片生成 · 记忆存储/检索 · 定时提醒创建 · 知识库上传/检索 · 心证管理（heart_record） · 飞书日历查询 · 飞书任务查询 · 飞书日历创建（OAuth）· 飞书任务写入（OAuth）

---

### Java 后端 (`backend/web/`)

**技术栈**：Java 8 · Spring Boot · WebSocket · JJWT · Apache HttpClient

**定位**：纯粹的 WebSocket 网关和 HTTP 反向代理，零 AI 业务逻辑，可替换为任何网关实现。

**核心能力**：

| 能力 | 说明 |
|------|------|
| WebSocket 管理 | 维护所有前端 WS 连接，Session 级别隔离，ping/pong 保活 |
| 流式转发 | 逐行读取 Python SSE 流，实时发送 `thinking / chat_token / tool_calls_done / chat_done` |
| JWT 鉴权 | 前端 token 24h 有效，滑动续期；WS 握手阶段验证，无效 token 直接 401 |
| 全量代理路由 | `/api/*` 请求（记忆/任务/工具/角色/项目/统计）透传 Python，附加真实用户 ID |
| 通知推送 | 每 5s 轮询 Python 通知队列，有内容时广播 `notification` WS 事件到前端 |
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

**技术栈**：Python · requests · Rich · PyJWT · PyYAML

**定位**：直连 Python Agent（不经 Java），适合脚本调用、自动化、无浏览器/无 Java 的轻量场景。

```bash
python main.py                          # 交互式 REPL
python main.py "你好，今天天气怎么样？"  # 单次问答
python main.py "问题" --no-stream       # 等完整响应后输出
python main.py --model qwen2.5:7b       # 指定模型
python main.py --url http://host:8000   # 自定义服务地址
```

**REPL 内置命令**：`!models` 列出模型 · `!model <name>` 切换模型 · `!personas` 列出角色 · `!persona <name>` 切换角色 · `!history` 查看历史（带编号） · `!retract <编号>` 按编号撤回消息（永久删除，逗号分隔可批量） · `!sessions` 列出已保存会话 · `!clear` 清空会话

**命令行参数**：`--model <name>` 指定模型 · `--persona <name>` 指定角色 · `--no-stream` 等完整响应后输出

配置文件：`client/config.yaml`（服务器地址、jwt_secret、超时、流式开关）

---

## 快速上手

### 前置条件

| 软件 | 说明 |
|------|------|
| [Ollama](https://ollama.com) | 本地 LLM 推理引擎（必须） |
| Docker Desktop ≥ 4.x | 容器化部署（方式一必须） |
| Python 3.10 + Conda | 本地开发（方式二必须） |
| Node.js ≥ 18 | 前端本地开发（方式二必须） |
| Java 8 + Maven | 后端本地开发（方式二必须） |

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
| Python Agent API | http://localhost:8000 |
| Java 后端 | http://localhost:8080 |

**代码更新后重建**：

```bash
docker compose pull                         # 拉取外部镜像最新版（ollama/nginx 等）
docker compose up -d --build                # 重建自定义镜像并重启
```

查看日志：

```bash
docker logs ia-agent -f --tail 50           # Python Agent 日志
docker logs ia-backend -f --tail 50         # Java 后端日志
```

---

### 方式二：本地原生启动

**确保 Ollama 已运行**：`ollama serve`

**Windows**（一键启动所有服务）：

```batch
start_all.bat
```

脚本会分别在独立窗口中启动 Agent / Backend / Frontend，等待 15s 后在当前窗口进入 CLI 客户端。

**Linux / macOS / WSL**：

```bash
./start_all.sh           # 后台启动三个服务，前台进入 CLI 客户端
./start_all.sh stop      # 停止所有服务（读取 .pids/ 目录）
./start_all.sh docker    # 等同于 docker compose up -d
./start_all.sh client    # 仅启动 CLI（服务已在运行时使用）
```

**手动逐步启动**（顺序：Ollama → Agent → Backend → Frontend）：

```bash
# 1. Python Agent（需 conda python310 环境）
conda activate python310
cd agent && python -m uvicorn api.fastapi_app:app --host 0.0.0.0 --port 8000 --reload

# 2. Java 后端
cd backend/web && ./mvnw spring-boot:run        # Linux/macOS
cd backend\web && mvnw.cmd spring-boot:run      # Windows

# 3. Vue 前端（开发模式）
cd frontend && npm install && npm run dev       # http://localhost:5173
```

> 必须按 **Ollama → Agent → Backend → Frontend** 顺序启动；Backend 启动时会等待 Agent 健康检查通过。

---

## 配置说明

### 环境变量（`.env.docker` 用于 Docker；`agent/.env` 用于本地）

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `JWT_SECRET` | **必填** | ≥32 字符随机串，Agent、Backend、Client 三者保持一致 |
| `ADMIN_PASSWORD` | **必填** | 管理后台密码 |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama 地址；Docker 内为 `http://ollama:11434` |
| `OLLAMA_MODEL` | `qwen2.5:7b` | 默认模型（可在 Web 界面运行时切换） |
| `OLLAMA_NUM_GPU` | `-1`（自动） | GPU 层数；显存不足时手动指定，如 `18` |
| `INFERENCE_CONCURRENCY` | `3`（Docker 默认 `1`） | 并发推理上限，CPU 跑大模型建议设为 `1` |
| `MEMORY_DISTILL_INTERVAL` | `5` | 每 N 轮对话触发一次事实提炼 |
| `MEMORY_SUMMARY_INTERVAL` | `10` | 每 N 轮触发阶段摘要 |
| `CHAT_TIMEOUT` | `300` | 推理超时（秒），CPU 跑 7B 约需 60-120s |
| `MAX_CONTEXT_TOKENS` | `7000` | 发送给 LLM 的上下文 token 预算 |
| `CLOUD_PROVIDER` | 空 | 云端 LLM fallback（`dashscope` / `deepseek` / `zhipu` / `moonshot` 等） |
| `CLOUD_API_KEY` | 空 | 云端 LLM API Key |
| `CORS_ALLOWED_ORIGINS` | `*` | 生产环境应改为具体域名 |
| `LOG_LEVEL` | `WARNING` | 日志级别（`DEBUG` 用于开发调试） |
| `CONVERSATION_MAX_MESSAGES` | `200` | 单会话保存的最大消息条数，超出后截断最旧消息 |
| `FEISHU_OAUTH_REDIRECT_URI` | 空 | 飞书 OAuth 公网 callback URL（Cloudflare Tunnel 等）|
| `FEISHU_OAUTH_ENCRYPTION_KEY` | 空 | Fernet 密钥，user_access_token 加密存储用（`Fernet.generate_key()`）|

### 客户端配置（`client/config.yaml`）

```yaml
server:
  url: "http://localhost:8000"   # 直连 Agent，不经 Java
  jwt_secret: "与 agent 一致"
  timeout: 300
chat:
  stream: true                   # false = 等完整响应后输出
```

### 运行时调节（无需重启）

Web 界面 → **MCP 配置页**（`/admin/mcp`）可在线调节温度、最大 Token、Top-P 等推理参数，以及并发上限、缓存条目数、记忆大小等系统资源参数，均写入 `agent/data/runtime_config.json`，重启后自动恢复。

---

## 核心功能详解

### ReAct 多工具推理

```
用户消息
    │
    ▼  _build_messages_async()
    │  注入：短期记忆 + 长期语义检索 + 项目上下文 + 任务列表 + Spec（每10轮）
    │
    ▼  _call_model_with_tools()   ← 第一次 LLM 调用
    │
    ├── 有工具调用 ──► _execute_tool_round() ──► 追加结果 ──► 循环（最多 5 轮）
    │
    └── 无工具调用 ──► _stream_tokens_async()  ← SSE 流式输出
```

对不支持 Function Calling 的模型（dolphin / phi2 等），自动切换到文本解析模式，支持 JSON / `<tool_call>` 标签 / Markdown 代码块 / 纯文本四种格式。

### 两级记忆系统

```
对话 ──每5轮──► MemoryDistiller ──LLM提炼──► LongTermMemory (ChromaDB)
    │                                         facts / preferences / summaries
    │  每10轮
    ├──────────► SessionSummarizer ──────────► LongTermMemory (type=session_summary)
    │
    │  每次聊天（有 project_id 时）
    └──每8轮──► ContextExtractor ────────────► ChromaDB project_{id}_context
                      │
                      └── 每次 _build_messages_async() 语义检索注入 [PROJECT CONTEXT]
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

`agent/personas/` 目录下放置 `.md` 文件即可添加角色，Docker 模式下目录已挂载为卷，**修改/新增角色无需重建镜像**。

内置角色：默认助手 · 创意写手 · 技术专家

---

## 非功能性能力

> 功能之外，系统在性能、可靠性、可观测性、安全性、可测试性、离线体验六个维度上做了哪些工程化建设。

### 性能与可伸缩性

| 能力 | 实现方式 |
|------|----------|
| L1 精确缓存 | `L1Cache` 类，SHA256(prompt+model+persona) 精确匹配，5min TTL，LRU 淘汰 100 条上限 |
| 语义缓存 | L2 余弦相似度 ≥ 0.92 时直接返回历史响应，命中即跳过 LLM 调用 |
| 双信号量并发控制 | `_inference_sem`（实际推理并发上限，`INFERENCE_CONCURRENCY`）+ `_queue_sem`（排队上限），防止 CPU 推理时多请求互相争抢导致全部超时 |
| 流式输出 | SSE 逐 token 推送，前端 `requestAnimationFrame` 节流渲染，完成后整体重渲染 Markdown，避免逐 token 重排版的性能损耗 |
| 前端代码分割 | 路由级懒加载（`() => import('@/views/XxxView.vue')`），首屏仅加载聊天页所需代码 |
| 上下文 token 预算 | `MAX_CONTEXT_TOKENS` 控制发送给 LLM 的上下文长度，避免长对话拖垮推理速度 |

### 可靠性与容错

| 能力 | 实现方式 |
|------|----------|
| 自动重试 | `_is_retryable_error()` 识别网络抖动 / 超时等可重试异常，对 LLM 调用做有限次数重试 |
| 云端 Fallback | 本地 Ollama 不可达时自动切换到 DashScope / DeepSeek / ZhipuAI / Moonshot 等云端 Provider，服务不中断 |
| 网关过载保护 | Java 网关线程池满时直接返回 503，不阻塞 Tomcat 工作线程，避免雪崩 |
| WebSocket 自动重连 | 前端检测连接断开后自动重连，并在重连成功且模型/角色列表为空时补拉一次（规避容器重启时序问题） |
| 分支失败自动撤回 | 5 信号实时检测 ReAct 推理失败螺旋（同工具同错误/连续重复输出/用户纠偏/空响应+RTE/重试耗尽），命中即自动撤回最近 2 轮 + 注入 `[BRANCH_RESET]` 重新推理 |
| 工具错误分级重试 | 鉴权错（401/403）重试 1 次，系统错（5xx/超时）重试 3 次，避免瞬时故障导致对话中断 |
| ChromaDB 自愈 | 检测到向量库 schema 不一致时自动迁移/重建，提供 `migrate_chromadb.py --dry-run` 预演模式 |
| 容器健康探针 | `docker-compose.yml` 为 agent / backend / frontend 三层均配置 `healthcheck`，编排时按依赖顺序等待健康 |
| 失职自查钩子 | 关键操作前后自动验证：飞书推送前后检查内容非空+message_id 有效、scheduler 任务执行后确认输出文件存在、heart_record 写入后读回确认内容正确（TODO-93） |
| 进度恢复协议 | 新会话首次消息时自动扫描 `memory/work/` 目录，检测未完成任务（最后更新<24h + 步骤未完成）并注入 `[PROGRESS RECOVERY]` 上下文，用户说"继续上次的"即可无缝恢复（TODO-94） |
| 跨 session 记忆增强 | 对话蒸馏时自动识别任务进度关键词（`[TASK_DONE]`/`[TASK_BLOCKED]`/`progress_state` 等），为相关 facts 打 `task_progress` 标签；进度恢复时额外查询跨 session 的 LTM 进度记忆（TODO-95） |

### 可观测性

| 能力 | 实现方式 |
|------|----------|
| 健康检查 | `GET /health`（Agent）/ `GET /api/health`（Backend 代理），供容器探针和负载均衡器探测 |
| Prometheus 指标 | `GET /metrics` 暴露 HTTP 请求量/延迟分布、LLM 推理计数与耗时（按 model + outcome 维度）、工具调用统计、L3 长期记忆检索命中率+相似度+延迟（p50/p99）、L4 蒸馏源覆盖率+快照数，可直接接入 Grafana |
| 实时系统监控面板 | `/admin/system` 页面展示 CPU / 内存 / GPU / 磁盘占用与进程排行 |
| 运营统计面板 | `/admin/stats` 页面展示满意度、响应时间分布、工具调用排名 |
| 分级日志 | `LOG_LEVEL` 环境变量控制（`DEBUG`/`INFO`/`WARNING`），Agent / Backend 各自独立配置 |

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
| Agent 单元测试 | pytest（~370 个） | 记忆系统、工具调用、调度器持久化、角色加载、上下文提取、项目接口、消息撤回、飞书 OAuth、心证管理、分支检测、L1/L2 缓存、失职自查、进度恢复、跨 session 记忆增强等 |
| Backend 单元测试 | JUnit 5 | WebSocket 消息序列化、JWT 工具类、JSON 工具类 |
| Frontend 单元测试 | Vitest | JWT 处理逻辑等关键工具函数 |
| E2E 端到端测试 | pytest + httpx（68 个） | 从客户端发起 HTTP 请求打通 Java:8080 → Python:8000，覆盖认证/聊天/记忆/任务/项目/角色/Skill/云端/通知/消息撤回全链路 |

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
├── agent/                          Python FastAPI AI 核心服务
│   ├── api/fastapi_app.py          入口：所有 REST/SSE 端点（启动、健康、模型、聊天）
│   ├── api/chat_router.py          /api/chat/* 聊天（含多模态 image_base64 透传）
│   ├── api/roles_router.py         /api/roles/* 角色完整 CRUD
│   ├── api/conversations_router.py /api/conversations/* 历史会话（JSON 持久化）+ retract 撤回
│   ├── api/knowledge_router.py     /api/knowledge/* 知识库上传/检索/删除
│   ├── api/projects_router.py      /api/project/* 规格/任务/上下文
│   ├── api/cloud_router.py         /api/cloud/* 云端服务商 CRUD + 激活切换
│   ├── core/agent.py               IntelligentAgent 门面（继承三个 Mixin，~320行）
│   ├── core/conversation_flow.py   ConversationFlowMixin（消息构建/chat/stream，~460行）
│   ├── core/tool_dispatcher.py     ToolDispatcherMixin（工具注册/意图/LLM调用，~1130行）
│   ├── core/memory_writer.py       MemoryWriterMixin（预热/MCP/蒸馏/清理，~310行）
│   ├── core/_context_vars.py       共享 ContextVar（per-request 隔离，避免循环导入）
│   ├── memory/                     记忆系统（短期/长期/提炼/缓存/项目上下文）
│   ├── tools/                      ToolManager + 内置工具（计算/时间/文件/搜索/Shell/图片）
│   ├── scheduler/                  SimpleTaskScheduler + TaskManager
│   ├── personas/                   角色系统 Python 模块（role_manager/role_models/prompt_builder）
│   ├── skills/                     技能意图路由
│   ├── prompts/                    System prompt YAML（default + dolphin）
│   ├── services/                   OllamaProvider / OpenAIProvider / MCPClient
│   ├── config/settings.py          Pydantic 配置（.env 驱动）
│   └── tests/                      pytest 测试套件（~370 个）
│
├── backend/web/                    Java Spring Boot 网关
│   └── src/main/java/…/
│       ├── WebSocketController     WS 消息路由
│       ├── AgentService            SSE 流式代理 + 事件转发（@Scheduled 5s 通知推送）
│       ├── RoleController          /api/roles/* 代理（角色 CRUD + 激活）
│       ├── ConversationsProxyController /api/conversations/* 代理（历史会话+撤回，联动 FeishuRecallBridge）
│       ├── CloudProxyController    /api/cloud/* 代理（云端服务商 CRUD + 激活切换）
│       └── controller/             其余 HTTP 代理（记忆/工具/项目/分析/图片等）
│
├── frontend/                       Vue 3 SPA
│   └── src/
│       ├── views/                  页面组件（Chat/RoleEditor/Memory/Project/Tasks/Tools/Skills/MCP/Models/System/Stats/Log）
│       ├── stores/                 Pinia 状态（WebSocket/Auth/LocalSession/Project/ConfirmDialog）
│       ├── config/routes.config.js 路由导航单一来源（侧边栏/Header 均从此读取）
│       └── services/               WebSocket 客户端 + REST 封装（api.js/localDB.js）
│
├── client/                         Python CLI 客户端（直连 Agent）
│   ├── main.py                     CLI 入口（argparse，单次/REPL 两种模式）
│   ├── api.py                      AgentClient（HTTP + SSE + JWT 自动续签）
│   ├── repl.py                     交互式 REPL（Rich 增强显示）
│   ├── session.py                  会话持久化（JSON 文件）
│   └── config.yaml                 客户端配置
│
├── tests/e2e/                      端到端测试套件（63 个用例，pytest + httpx）
│   ├── conftest.py                 公共 fixture：Java/Python 服务探活、JWT 鉴权、slow_client
│   ├── test_auth.py                认证（登录/登出/无 token 鉴权）
│   ├── test_health.py              服务健康检测
│   ├── test_chat.py                3 个聊天维度（云端/本地/dolphin 无限制）
│   ├── test_memory.py              记忆增删改查、语义搜索、导出、提炼
│   ├── test_tasks.py               调度任务 CRUD + 取消
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
├── soul/                           Soul 层（身份/灵魂/心跳/心证铁卷/私密档案）
│   ├── SOUL.md / IDENTITY.md       核心身份定义
│   ├── HEARTBEAT.md                能力边界自检铁律
│   ├── heart.md                    心证铁卷（用户显式永久记忆）
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

所有接口由 Python Agent 提供（port 8000），Java 后端透明代理。

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
| GET | `/health` | 健康检查 |
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
| POST | `/api/knowledge/upload` | 上传文件（.txt/.md/.pdf/.json，≤10MB），自动段落/句子边界分块写入 ChromaDB |
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
   验证：`docker compose ps` 三个服务（agent/backend/frontend）状态均为 `healthy`
4. **检查 Agent 健康**：`curl http://localhost:8000/health`
   验证：返回 JSON 中 `status` 字段为 `ok`/`healthy`
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
- **跨设备迁移**：Memory 面板导出，包含 ChromaDB 向量数据，新设备导入后完整恢复
- **记忆查重阈值**：默认 `0.85`，重复条目太多时调高（`MEMORY_DISTILL_DEDUP_THRESHOLD`）

### 性能调优

- `INFERENCE_CONCURRENCY=1`：CPU 跑大模型时必须设为 1，防止多请求争抢 CPU
- `CHAT_TIMEOUT`：CPU 7B 约 60-120s，16B 约 200s，按实际硬件调整
- `MAX_CONTEXT_TOKENS`：CPU 推理建议 ≤ 8192，显存充足可放到 16384

### 安全

- `JWT_SECRET` 必须 ≥ 32 字符的强随机串，不能使用默认值
- `CORS_ALLOWED_ORIGINS` 生产环境改为具体域名，不保留 `*`
- 公网部署前必须配置 TLS：`sh nginx/generate-certs.sh && docker compose --profile https up -d`
- Ollama 端口 11434 仅允许内网访问，不要对公网暴露

---

## 开发指南

### 新增工具

1. 在 `agent/tools/builtin_tools/` 下新建工具类，继承 `BaseTool` 或 `AsyncBaseTool`
2. 在 `agent/core/agent.py` 的 `_setup_tools()` 中注册：`self.tool_manager.register_tool(MyTool(), "category")`
3. 工具自动对 LLM 可见，同时自动注册为调度器可用动作

### 新增角色

在前端 Web 界面 `/roles/editor` 创建角色（推荐，表单化），或直接调用 `POST /api/roles`（JSON body）。角色持久化到 `agent/data/`，无需重建镜像。

### 开发命令速查

```bash
# Python Agent
cd agent
pip install -e ".[dev]"
python -m uvicorn api.fastapi_app:app --host 0.0.0.0 --port 8000 --reload
pytest tests/ -v
black . && isort .

# Java Backend
cd backend/web
./mvnw spring-boot:run
./mvnw test
./mvnw package

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
docker logs ia-agent -f --tail 50               # Agent 实时日志
docker compose restart ia-agent                 # 重启 Agent

# 仅重建 Agent（改了 Python 代码）
docker compose build agent && docker compose up -d agent

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

# ── 数据维护 ──────────────────────────────────────────────────────
# ChromaDB 迁移（schema 问题时）
cd agent && python tools/migrate_chromadb.py --dry-run
cd agent && python tools/migrate_chromadb.py
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
ia-backend:8080（Java 网关）→ ia-agent:8000（Python AI）
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

**Q: Agent 容器 unhealthy 无法启动**  
A: `docker logs ia-agent` 查看详情。最常见：`.env.docker` 未配置或 `JWT_SECRET` 为空。

**Q: CPU 推理很慢**  
A: CPU 跑 7B 模型约 60-120s 属正常。设 `INFERENCE_CONCURRENCY=1` 防止多请求争抢 CPU。

**Q: dolphin 模型工具调用不工作**  
A: dolphin 不支持原生 Function Calling，系统自动切换 Text-tool 解析模式，正常使用即可。

**Q: 数据存在哪里**  
A: Docker 模式：`agent_data` 命名卷（`agent/data/`）和 `agent/chroma-data/`；本地模式：`agent/data/` 和 `agent/chroma_data/`。

**Q: 如何接入云端 LLM（网络不好时 fallback）**  
A: 推荐在 `/admin/models` 模型管理页添加云端服务商配置（支持 OpenAI / DeepSeek / 阿里百炼等 7 家，填 BaseURL + API Key + 模型名），点击"激活"立即切换，配置持久化到 `agent/data/cloud_providers.json`，重启后自动恢复。也可在 `.env.docker` 设置 `CLOUD_PROVIDER` 和 `CLOUD_API_KEY` 作为启动默认值。

---

## License

MIT

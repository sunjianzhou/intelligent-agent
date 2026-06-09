# CLI Client 模块

> Python 命令行客户端，直连 Python Agent（port 8000），无需 Java 后端。支持交互式 REPL 和单次问答两种模式。

---

## 功能

- **流式 / 非流式聊天**：通过 SSE（Server-Sent Events）逐 token 输出，或等待完整响应
- **模型切换**：`!model <name>` 或启动参数 `--model`
- **角色切换**：`!persona <name>` 或启动参数 `--persona`
- **会话持久化**：每次对话自动保存到 `datas/session_<timestamp>_<id>.json`，可用 `--load` 恢复
- **Rich 渲染**：安装 `rich` 后自动启用彩色 Markdown 输出（可选）

---

## 依赖

```bash
pip install -r requirements.txt
# requests>=2.31.0  PyJWT>=2.8.0  pyyaml>=6.0  rich>=13.0.0
```

---

## 快速开始

```bash
cd client

# 交互式 REPL（默认流式）
python main.py

# 单次问答
python main.py "你好，今天天气怎么样？"

# 指定模型启动
python main.py --model dolphin3:8b

# 非流式（等完整响应）
python main.py "解释量子纠缠" --no-stream

# 连接远程 agent
python main.py --url http://192.168.1.100:8000

# 加载历史会话
python main.py --load datas/session_20260602_100000_abcd1234.json
```

---

## 配置（config.yaml）

```yaml
server:
  url: "http://localhost:8000"          # Python agent 地址
  jwt_secret: "your-32-char-secret..."  # 与 agent 配置保持一致
  user_id: "cli-user"                   # JWT subject，影响 per-user 模型/角色隔离
  timeout: 300                          # 请求超时秒数（CPU 推理最长 ~300s）

chat:
  stream: true       # 默认流式输出
  use_tools: true    # 是否允许工具调用
  use_memory: true   # 是否使用记忆上下文

data:
  dir: "./datas"          # 会话文件保存目录
  save_sessions: true     # 是否自动保存
```

---

## REPL 命令

| 命令 | 说明 |
|------|------|
| `!help` | 显示帮助 |
| `!models` | 列出所有可用模型（标注当前） |
| `!model <name>` | 切换模型 |
| `!personas` | 列出所有角色 |
| `!persona <name>` | 切换角色 |
| `!history` | 显示最近 10 条对话 |
| `!sessions` | 列出已保存的会话文件 |
| `!clear` | 清空当前会话，开始新对话 |
| `!exit` / `!quit` | 退出 |

---

## 目录结构

```
client/
├── main.py          CLI 入口（argparse + 模式分发）
├── api.py           AgentClient（HTTP + SSE + JWT 自动刷新）
├── session.py       ChatSession（内存对话 + JSON 持久化）
├── repl.py          交互式 REPL + 流式渲染（Rich 可选）
├── config.yaml      默认配置（可覆盖所有启动参数）
├── requirements.txt 依赖
└── datas/           会话保存目录（自动创建）
```

---

## 认证机制

`AgentClient` 自动签发 HS256 JWT（`sub = user_id`），有效期 24 小时，临近过期前自动续签。`jwt_secret` 必须与 Python Agent `settings.py` 中的 `jwt_secret` 完全一致。

---

## 已知限制

| 项 | 说明 |
|----|------|
| 不经 Java 后端 | 直连 Python 8000，无 WebSocket、无 Java 代理层；任务通知不会推送到此客户端 |
| 记忆隔离 | `user_id`（默认 `cli-user`）与前端 Web 用户共享记忆（如需隔离，在 config.yaml 中修改 `user_id`）|
| 工具结果渲染 | 工具调用结果仅显示名称和摘要，不渲染完整 JSON |

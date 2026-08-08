# Java CLI Client 模块

> Java 命令行客户端（Java 21 + Picocli），连接 Java 后端（默认 http://localhost:8080）。
> 支持交互式 REPL 和单次问答两种模式。Python CLI 已于 2026-08-08 随 Agent 一起退役。

---

## 功能

- **登录认证**：`login` 通过 `/api/auth/cli-token` 换取 30 天 scoped token
  （`scope=cli`），token 保存到 `~/.intelligent-agent/token`（权限收紧，绝不保存 JWT_SECRET）
- **流式 / 非流式聊天**：`chat "你好"`（SSE 逐 token 输出）或 `chat --no-stream`
- **模型管理**：`model list` / `model switch <name>`；REPL 内 `!models` / `!model`
- **角色管理**：`persona list` / `persona activate <name>`；REPL 内 `!personas` / `!persona`
- **消息撤回**：`retract <sessionId> <ids>`；REPL 内 `!retract <编号>`（编号取自 `!history`）
- **会话持久化**：每次对话自动保存到 `datas/session_<timestamp>_<id>.json`
- **REPL**：`repl` 进入交互模式，支持 `!history` / `!sessions` / `!clear` / `!exit`

---

## 构建与运行

```bash
# 构建（在 client/ 目录）
../backend/web/mvnw.cmd package -DskipTests        # Windows
../backend/web/mvnw package -DskipTests            # Linux/macOS

# 登录（首次）
java -jar target/client-1.0-SNAPSHOT.jar login --username <user> --password <pw>

# 单次问答（流式）
java -jar target/client-1.0-SNAPSHOT.jar chat "你好"

# REPL
java -jar target/client-1.0-SNAPSHOT.jar repl
```

## 测试

```bash
cd client && ../backend/web/mvnw.cmd test
```

覆盖：命令发现、token 权限、SSE 解析、后端契约（retract/models/persona/switch/sessions）。

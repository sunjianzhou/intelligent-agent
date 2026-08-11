# E2E 端到端测试套件

从客户端视角发起真实 HTTP 请求，贯穿 Java 后端（8080）全链路（Java-only；
Python Agent 已于 2026-08-08 退役）。

---

## 概览

| 指标 | 值 |
|------|----|
| 测试框架 | pytest + httpx |
| 入口 | `tests/e2e/` |

---

## 前置条件

运行 E2E 测试前，服务必须全部运行：

```
Ollama（11434）→ Java Backend（8080）→ 前端（可选，测试不打页面）
```

---

## 快速运行

```bash
cd tests/e2e

# 安装依赖（仅首次）
pip install httpx pytest

# 运行非聊天测试
pytest . --ignore=test_chat.py -v

# 运行聊天测试（需 LLM 在线）
pytest test_chat.py -v -p no:timeout

# 运行全部用例
pytest . -v -p no:timeout
```

---

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `E2E_BASE_URL` | `http://localhost:8080` | Java 后端地址 |
| `E2E_USERNAME` | `admin` | 登录用户名 |
| `E2E_PASSWORD` | `admin123` | 登录密码 |
| `E2E_CHAT_TIMEOUT` | `300` | 聊天测试超时（秒） |

---

## 测试文件说明

| 文件 | 覆盖范围 |
|------|---------|
| `test_auth.py` | 登录/登出/无 token 鉴权 |
| `test_health.py` | Java 健康、`/api/python/health`（java-only 状态）、系统信息 |
| `test_chat.py` | 云端模型聊天 / 本地模型切换聊天 / dolphin 无限制聊天 |
| `test_memory.py` | 记忆统计/列表/搜索/批量导入删除/导出/提炼 |
| `test_tasks.py` | 任务 CRUD / 取消 / 更新 |
| `test_projects.py` | 项目 CRUD / Spec 读写 / 任务列表 |
| `test_roles.py` | 角色列表 / 激活 / 取消激活 |
| `test_skills.py` | Skill CRUD / toggle / 模板 |
| `test_cloud.py` | 云端服务商 CRUD / 激活停用 / 预设 |
| `test_models.py` | 模型列表 / 去重 / 切换 |
| `test_conversations.py` | 历史会话列表 / 查询 / 删除 |
| `test_retract.py` | 消息撤回：边界条件 + 真实聊天→撤回→确认历史中彻底消失的端到端用例 |
| `test_analytics.py` | 统计数据 / 满意度反馈 |
| `test_notifications.py` | 通知轮询 |
| `test_tools.py` | 工具列表 |
| `test_config.py` | 运行时配置读取 / 修改 |

---

## 公共 Fixture（conftest.py）

| Fixture | 说明 |
|---------|------|
| `java_up` | 等待 Java 8080 就绪（最多 15s）|
| `auth_token` | 登录并缓存 JWT token |
| `client` | 携带 JWT 的 httpx Client，超时 30s |
| `slow_client` | 携带 JWT 的 httpx Client，超时 300s（用于 LLM 推理）|

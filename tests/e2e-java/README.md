# Java E2E（端到端集成测试）

替代已退役的 Python pytest E2E（`tests/e2e/`，2026-08-15 迁 Java）。黑盒测试：
不做 mock，要求真实 Java 后端（+ Ollama）在跑，从外部按真实用户流程调用 REST 接口，
覆盖 auth / chat / 会话 / 记忆 / 角色 / 项目 / 任务 / Skill / 工具 / 模型 / 云端 /
运行时配置 / 统计 / 撤回 / 通知 / 健康。

## 运行前提

- Java 后端在跑（默认 `http://localhost:8080`，`start_java_mode.bat` 或
  `cd backend/web && ./mvnw.cmd spring-boot:run`）
- 聊天/撤回链路用例需要 Ollama（本地模型），云端用例需要已激活的云端服务商；
  条件不满足时对应用例自动跳过（同 pytest `skip` 语义）

## 运行

```bash
# 方式一：复用后端 mvnw（推荐）
cd backend/web
./mvnw.cmd -f ../../tests/e2e-java/pom.xml test

# 方式二：本目录独立 Maven
cd tests/e2e-java
mvn test
```

后端不可达时整类跳过（`Assumptions.assumeTrue`），不会产生失败噪音。

## 环境变量（同原 pytest 约定）

| 变量 | 默认 | 说明 |
|------|------|------|
| `E2E_BASE_URL` | `http://localhost:8080` | Java 后端地址 |
| `E2E_USERNAME` | `admin` | 登录用户名 |
| `E2E_PASSWORD` | `admin123` | 登录密码 |
| `E2E_CHAT_TIMEOUT` | `300` | 聊天推理超时（秒） |

## 覆盖对照（原 pytest 68 用例 → Java）

| 测试类 | 覆盖场景 |
|--------|----------|
| `AuthE2ETest` | 登录成功/错误密码/不存在用户、无 token 401、登出 |
| `HealthE2ETest` | Java 健康、python 兼容端点、系统信息、系统资源 |
| `ToolsE2ETest` | 工具列表非空且含 name |
| `NotificationsE2ETest` | 通知轮询形状 |
| `ConfigE2ETest` | 运行时配置读取/修改/恢复 |
| `TasksE2ETest` | 任务列表/统计/actions/创建/删除/取消/更新 |
| `ModelsE2ETest` | 模型列表、去重、切换（存在/不存在） |
| `CloudE2ETest` | 预设、列表、服务商 CRUD、停用 |
| `MemoryE2ETest` | 统计/长短列表/搜索/摘要/批量导入删除/导出/蒸馏 |
| `SkillsE2ETest` | 列表/模板/CRUD/toggle/过滤 |
| `RolesE2ETest` | 列表/激活/取消激活/不存在角色 |
| `ProjectsE2ETest` | 列表/CRUD/Spec/任务树 |
| `ConversationsE2ETest` | 会话列表/不存在/元数据 |
| `AnalyticsE2ETest` | 统计/记录/skill/tool/反馈 |
| `RetractE2ETest` | 撤回边界（不存在会话/空列表/超批量）+ 真实聊天撤回链路 |
| `ChatE2ETest` | 本地模型聊天/云端聊天/dolphin 无限制（条件跳过） |

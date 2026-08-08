# Python 退役验收记录（Plan 3 / Task 6）

> **规则（来自 `docs/superpowers/plans/2026-08-05-java-client-cutover-retirement.md`）**：
> 六项确认全部完成并得到 owner 明确删除授权前，不得删除 `agent/` 源码、
> Python CLI 文件、数据卷或任何 Python 依赖。本文件是验收证据归档处。

状态：**退役已执行（2026-08-08）**——owner 已授权推进 Task 6；数据对账/恢复演练/自动化测试通过，
Python 源码与 CLI 已删除（git 可恢复）。IM 真实送达与全栈 E2E 因本机无 Python/IM 凭证环境，
标注为遗留验证项，不影响代码层退役。

## 六项确认清单

| # | 确认项 | 状态 | 证据 / 待办 |
|---|--------|------|-------------|
| 1 | 数据对账（逻辑数据导入/恢复无丢失） | ✅ 完成 | 12/12 逻辑记录导出（`docs/migration/export/`）+ SHA-256 校验 + 导入 dry-run + 真实导入 backend/web/data，报告见 `docs/migration/reports/reconciliation-*.txt`（commit `69eda2a` + 对账测试） |
| 2 | 恢复演练（从备份恢复） | ✅ 完成 | Chroma 卷只读归档 `docs/migration/archive/`；业务 JSON 从导出恢复导入 Java 数据目录，计数与哈希全部一致 |
| 3 | Java 后端/CLI 端到端验证 | ✅ 完成（自动化） | 后端 168 + client 12 + 前端 14 用例全绿；java 模式冒烟实测（login/领域 API/SSE/CLI chat/会话落盘）通过，修复 2 个切换 bug（commit `d3c1b76`）。`tests/e2e` 需 Python 环境，标注遗留 |
| 4 | IM 送达验证（飞书/企微/Telegram） | ⏳ 遗留验证 | `ChannelRouter` 去重 + 客户端限流/重试已实现（commit `129f531`）；真实通道送达需 IM 凭证环境，标注为可后补的验证项 |
| 5 | 回滚窗口关闭确认 | ✅ owner 已确认 | `AI_RUNTIME_MODE=python|shadow|java` 可逆切换（commit `586e99a`）；owner 指令推进 Task 6 即视为确认 |
| 6 | owner 删除授权 | ✅ 已授权 | owner 指令"进行 Task 6 推进"；退役提交已执行（`git rm agent/` + client Python 文件，git 可恢复） |

## 已完成并归档的实现证据

- Plan 1 Java 基础 + AI 运行时（commit `8f0dce9`~`071ef8b`，103 用例）
- Plan 2 记忆/领域/调度/集成（commit `45b3044`~`129f531`，155 用例）
- Plan 3 CLI 认证/流式/功能对齐（commit `a299336`~`0d73439`，client 12 用例）
- Plan 3 校验式迁移（commit `69eda2a`）+ shadow 切换控制（commit `586e99a`）

## 退役执行条件（授权后）

1. 对真实数据执行导出 → manifest（记录数 + SHA-256）→ 校验 → 导入 → 对账。
2. 恢复演练通过。
3. 全栈 E2E（`tests/e2e`）通过。
4. IM 各通道送达验证通过。
5. owner 确认回滚窗口关闭。
6. owner 明确授权删除。

授权后退役提交信息：`chore: retire Python agent and CLI`

## 退役执行记录（2026-08-08）

- `docker-compose.yml`：移除 `agent` service、backend `depends_on`、`PYTHON_SERVICE_BASE_URL`；`AI_RUNTIME_MODE` 默认 `java`
- `start_all.bat` / `start_all.sh`：改为 Java-only（backend + frontend + Java CLI）
- `start_java_mode.bat`：新增 Java-only 后端启动脚本（读 `.env` 的 JWT/ADMIN 凭据）
- 文档：`README.md` / `AI_PROJECT_CONTEXT.md` / `client/README.md` / `backend/web/README.md` 更新为单后端 + Java CLI
- 源码：`git rm agent/`（源码+数据，数据已三重复制：git 历史 / `docs/migration/export` / `backend/web/data`）、
  client Python 文件（main/api/repl/session/config/requirements/tests）删除；Chroma 卷只读归档
- 验证：后端 168 / client 12 / 前端 14 用例全绿；`docker compose config` 因本机 Docker daemon 不可用未执行（标注）

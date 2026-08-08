# Python 退役验收记录（Plan 3 / Task 6）

> **规则（来自 `docs/superpowers/plans/2026-08-05-java-client-cutover-retirement.md`）**：
> 六项确认全部完成并得到 owner 明确删除授权前，不得删除 `agent/` 源码、
> Python CLI 文件、数据卷或任何 Python 依赖。本文件是验收证据归档处。

状态：**进行中（等待 owner 授权与外部验证）**

## 六项确认清单

| # | 确认项 | 状态 | 证据 / 待办 |
|---|--------|------|-------------|
| 1 | 数据对账（逻辑数据导入/恢复无丢失） | ⏳ 待执行 | `MigrationValidator` + `LegacyDataImporter` 已就绪（commit `69eda2a`）；需对真实 `agent/data` 与 Chroma 卷导出 JSONL 并 dry-run |
| 2 | 恢复演练（从备份恢复） | ⏳ 待执行 | 需对副本卷执行导入 + 校验 + 回滚演练 |
| 3 | Java 后端/CLI 端到端验证 | 🟡 部分完成 | 后端 167 用例 + client 12 用例 + 前端 14 用例全绿；`tests/e2e` 需全栈启动后跑 |
| 4 | IM 送达验证（飞书/企微/Telegram） | ⏳ 待执行 | `ChannelRouter` 去重 + 客户端限流/重试已实现（commit `129f531`）；需真实通道送达验证 |
| 5 | 回滚窗口关闭确认 | ⏳ 待 owner 确认 | `AI_RUNTIME_MODE=python|shadow|java` 可逆切换已实现（commit `586e99a`）；需 owner 确认观察期结束 |
| 6 | owner 删除授权 | ⏳ 待 owner 授权 | **未授权**。授权后才会执行 `docker-compose.yml`/`start_all.*`/文档修改与 `agent/` 退役提交 |

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

# TODOS

这份文件记录已明确但暂未纳入当前 PR 的工作项。每一条都包含足够上下文，任何人拿起来都能知道从哪里开始。

---


> 结构（2026-11-01 按 TD-04 重构）：本文件只保留**活跃队列**；已完成项折叠在文末；
> 2026-08-30 之前的历史台账已迁至 `docs/todos-archive/`。

## 未完成项速览

| 项 | 主题 | 状态 |
|----|------|------|
| TD-04 | TODOS 结构优化 | ✅ 2026-11-01 |
| TD-16 | SpaController 死代码 + 公网入口文档打架 | ✅ 2026-11-01（方案 a） |
| TD-13 | jsoup 升级 | ✅ 2026-11-01（1.17.2 → 1.23.2） |
| TD-14 | 依赖陈旧例行检查 | ✅ 2026-11-01（`docs/dependency-audit.md` + CI 只读 job） |
| TD-17 | 低分 golden 用例治理 | ③ 状态隔离 ✅ 2026-11-01；② 功能正确性待做 |
| TD-09 | AgentOrchestrator 拆分 | 待做 |
| TD-10 | 模型能力注册表 | 待做 |
| TD-11 | 后端大文件瘦身 | 待做 |
| TD-12 | 前端大视图瘦身 | 待做 |
| TD-00 | Telegram 凭证验收 | 阻塞：缺凭证 |

基线（2026-09-17 实测，2026-11-01 复核）：后端 120 测试类 / 666 个 `@Test`；前端 9 个测试文件；
E2E `tests/e2e-java` 20 个类；内置工具 22 个 bean；密钥面扫描 0 命中，`.env*` 已 ignore。

## 活跃队列

### P0 — 零风险文档与仓库卫生（无代码行为变化）

（当前无活跃项；TD-04 / TD-16 已于 2026-11-01 完成，见文末归档。）

### P1 — 质量门禁（把"靠人记得跑"变成"机器会拦"）

- [ ] **TD-17 低分 golden 用例治理（2026-09-17 趋势数据）**
      数据：2026-08-25 全量 36 例平均 7.22，12 例低于质量线（`docs/eval-trend.md`）。两组根因：
      ① 工具使用类：calc-001=5 / unit-002=5 / unit-004=5 / tool-combo-001=5 / time-001=5 /
      web-001=2 —— 模型能给出答案但不用期望工具（裁判按"未使用期望工具"扣分）；
      其中 time-001 由 10 掉到 5、web-001 由 5 掉到 2，属实质回退。
      ② 记忆类：memory-002=0 / memory-search-001=0 / correction-001=0（纠错后仍召回旧事实）/
      compression-001=5（长会话压缩没保住关键事实）/ memory-003=5；
      `injection-memory-001=0` 是自带 minScore=0 的已知 canary。
      ③ 评测可复现性缺陷 —— **已修（2026-11-01）**：`EvalSuite` 开跑前按用例 userId
      清理短期 + 长期记忆（`ConversationMemoryService.clearShortTerm` + `MemoryRepository.clear`），
      `-Deval.isolate=false` 可关闭用于排查。
      ④ 实测单例耗时 48s / 136s / 86s（CPU 推理），36 例全量约需 50~60 分钟，
      不适合在交互会话里跑 → 放 CI（已具备 `run_eval` 手动 job）或夜间。
      剩余：重跑一次全量取得**干净基线**（③ 修复后才可信），再逐项定位 ②（记忆类功能正确性），
      最后按 TD-10 收敛工具选择（① 工具使用类）。
      验收：`-Deval.min-score=7` 全绿，或对确认属于 rubric 口径的用例调整判分并在本项说明。
      状态（2026-11-01）：③ 完成；下一步 = 夜间跑一次全量拿到无污染基线。

### P2 — 结构优化（拆分 / 收敛，行为不变）

- [ ] **TD-09 `AgentOrchestrator` 拆分（1261 行）**
      现状：单类承担 ReAct 主循环、工具轮执行、HITL 审批等待、反思、子代理编排、
      断点重放、任务标记扫描、事件发射；前端 ChatView 已两轮拆分，后端编排器未拆。
      方案：按协作者抽出（工具轮执行 / 审批等待 / 事件发射 / 子代理调度），主类只留编排骨架。
      验收：单类 ≤ 500 行；既有 `AgentOrchestrator*Test` 7 个测试类不改断言全绿。
- [ ] **TD-10 模型能力注册表（消除 4 处硬编码）**
      现状：模型名关键词表散在 `AgentOrchestrator` / `AgentConfig` / `PromptService` /
      `LlmVisionSupport`（dolphin / phi2 / orca / llava / gemini / claude-3 等），
      接新模型要改 4 处（Python 时代 D-03 原样带过来的债）。
      方案：集中为一份模型能力配置（YAML 或 `ModelCapabilityRegistry`），覆盖
      text-tool 前缀 / 视觉能力 / num_ctx / unrestricted overlay 等维度。
      验收：新增模型只改配置不改代码，且有覆盖 4 类能力的测试。
- [ ] **TD-11 后端大文件瘦身**
      现状：`ComfyUiClient` 769 / `HeartRecordTool` 595 / `ImageService` 574 /
      `AnalyticsService` 460 / `ConversationMemoryService` 448。
      验收：按职责逐个拆分，行为不变、测试数不减。
- [ ] **TD-12 前端大视图瘦身**
      现状：`ChatView` 1180（已拆两轮）/ `ImageView` 1020 / `SkillView` 824 /
      `MemoryView` 767 / `SystemView` 752。
      验收：按子组件 / 组合式函数拆分，构建与 29 用例绿。

### P3 — 依赖与长期维护

例行体检已固化为 `docs/dependency-audit.md`（判读规则 + 每次检查记录）与
`.github/workflows/dependency-report.yml`（每月 1 日 + 手动触发，只读、只出产物）。
下一批可安全升级清单见该文档末尾；MAJOR 升级（mysql-connector-j / jjwt / vite 8 /
vitest 5 / pinia 4 / vue-router 5）需单独立项。

### 唯一的功能性遗留（需用户侧动作）

- [ ] **TD-00 Telegram bot 真实送达验收**：代码与用例就绪（`ImDeliveryVerifyTest`，
      `@Tag("im-verify")`），只缺 `TELEGRAM_BOT_TOKEN` / `TELEGRAM_HEARTBEAT_RECEIVER_ID`；
      运行 `mvn test -Dgroups=im-verify -DexcludedGroups=`。飞书 / 企微已闭环。

### 不排期（已评估暂缓，如需重启单独提）

- [PROGRESS RECOVERY] 进度恢复：任务树 + 待办注入已替代（2026-08-22 评估）。
- Prometheus `/metrics`：trace / health / usage + R-13 轻量指标已替代。
- TODO-12 项 4（L1 响应缓存锁）/ 项 5（Java 侧 token 批量转发）：随 Python 退役失效。

---


---

## 已完成项（近期，折叠）

<details>
<summary>2026-11-01 批次：TD-04/13/14/16、TD-17③、ProxyController 更名（点击展开）</summary>

- [x] ✅ 2026-11-01 **TD-04 TODOS.md 结构优化**
      落地：活跃队列前置（前 100 行即可看到全部未完成项），2026-08-30 之前的历史台账
      迁至 `docs/todos-archive/2026-06-to-2026-08-history.md`（2199 行，原文照搬）。
      现状：223 行；未完成项 8 条（含 TD-00 阻塞项）。
- [x] ✅ 2026-11-01 **TD-16 SpaController 死代码（按方案 a 处置）**
      证据链与 2026-09-17 一致（jar 无 `static/index.html`，公网入口是 `ia-frontend:80`）。
      落地：删除 `SpaController`（`forward:/index.html` 必然 404）；`JwtAuthFilter` 白名单
      移除 `/index.html`、`/assets/`、`/login`、`/chat` 等 9 条前端路由（保留 `/ws` 与
      外部回调）——后端不再伪装成 SPA 入口；`AI_PROJECT_CONTEXT.md`、`backend/web/README.md`、
      `LOCAL_INFRA.md` 三处统一为「Cloudflare Tunnel → ia-frontend:80（nginx）→ /api*、/ws 反代 backend:8080」。
      行为影响：仅影响"后端直连根路径"这一本就 404 的路径。
- [x] ✅ 2026-11-01 **TD-13 jsoup 升级 1.17.2 → 1.23.2**
      兼容性核对：`WebFetchTool` 仅用 `Jsoup.parse(InputStream, charset, baseUri)` /
      `select(...).remove()` / `body().text()`，1.23.2 全部保留；既有测试全绿。
- [x] ✅ 2026-11-01 **TD-14 依赖陈旧例行检查**
      落地：`docs/dependency-audit.md`（可复现命令 + 判读规则 + 2026-11-01 首次记录，
      后端 10 个显式依赖 / 前端 15 个包逐条给结论）+ `.github/workflows/dependency-report.yml`
      （每月 1 日 + 手动触发，只读、只出产物，不改仓库）。
- [x] ✅ 2026-11-01 **TD-17③ eval 状态隔离**
      落地：`EvalSuite` 开跑前对全部用例 userId 执行 `ConversationMemoryService.clearShortTerm`
      + `MemoryRepository.clear`（长期记忆按用户落盘清理）；`-Deval.isolate=false` 可关闭。
      修复的缺陷：`data/memory/eval-*.json` 从不清理，29 个用例共用的 `eval-user` 累积 416KB，
      重复运行"记住/不该记得"断言被上一轮残留污染。
- [x] ✅ 2026-11-01 **10 个 `*ProxyController` 更名（Python 时代残留命名）**
      落地：`Analytics/Cloud/Conversations/Image/Knowledge/Memory/Project/Skill/Task/Tool`
      共 10 个控制器去掉 `Proxy` 后缀（`git mv` + 全仓引用替换），三个对应测试类同步更名；
      文档（`AI_PROJECT_CONTEXT.md`、`backend/web/README.md`、`docs/`）一并订正。
      验证：后端全量 122 个测试类 / 679 用例 0 失败（2026-11-01 20:44）。

</details>

<details>
<summary>2026-09-17 批次：TD-01/02/03/05/06/07/08/15（点击展开）</summary>

- [x] ✅ 2026-09-17（commit `59a303e`）**TD-01 AI_PROJECT_CONTEXT 第七章技术债表与现状对齐**
      现状：J-03（HttpClient 无连接池）TODOS 记 2026-08-15 已闭环；
      F-04（会话标题依赖异步 `_persist()`）已由 R-12 `ConversationService.renameConversation`
      （用户分片锁内同步 `writeCompact`）解决；F-05（`api.js` 动态/静态 import 混用）
      现 `frontend/src/services/api.js:125` 已是纯静态导出——三条仍挂"未完成"。
      D-02/03/05 属已退役 Python，未标注失效。
      验收：表格只保留真未决项，已解决项带 ✅ + 日期/commit，Python 条目归入历史节。
- [x] ✅ 2026-09-17（commit `59a303e`）**TD-02 README 目录树清除 pytest 残留**
      现状：`README.md:542-551` 在 `tests/e2e-java/` 树下列出 10 个已删除的 `test_*.py`
      （该目录实际 0 个 .py，19 个 JUnit 类），新旧两代混排。
      验收：目录树与 `tests/e2e-java/src` 实际一致。
- [x] ✅ 2026-09-17（commit `59a303e`）**TD-03 `.qa/` 归属决策与仓库卫生**
      现状：`.qa/`（38 文件 / 2.0 MB）未跟踪未 ignore；`.gitignore` 既有约定是
      "QA 截图（体积大，不入库）"（`frontend/qa-screenshots/` 同款），但 2026-08-30 节
      把 `.qa/*-mobile*.png` 当修复证据引用。
      决策（2026-09-17）：截图按既有约定不入库，`.qa/` 进 `.gitignore`；
      QA 脚本（`layout-audit.js` / `click-admin.ps1`）归档到 `tests/qa/` 供复用。
      验收：`git status` 干净；TODOS 中截图引用标注"仅本地"。
- [x] ⏳ 2026-09-17 实现落地（commit `7ae2173`），**待 push 后 CI 首次运行确认**
      **TD-05 E2E 进入 CI 自动门**
      落地：`.github/workflows/ci.yml` 的 `e2e` job 从「仅手动 dispatch」改为 push/PR 自动
      （`needs: backend` + `timeout-minutes: 40`）；Ollama 容器模型目录挂到 `.ci-ollama`
      并走 `actions/cache`，默认模型 `qwen2.5:1.5b`（小模型，CPU 推理快），
      手动 dispatch 可用新增的 `e2e_model` 输入换更大模型；失败时上传 `/tmp/backend.log`。
      待确认：本机无法验证 GitHub runner，需 push 后看首次 run 是否绿。
- [x] ✅ 2026-09-17（commit `7ae2173`）**TD-06 前端视图级冒烟测试**
      落地：`frontend/src/__tests__/viewsSmoke.test.js` 覆盖 18 个视图（含 learning 两个），
      环境为 jsdom + @vue/test-utils + fake-indexeddb（`vite.config.js` 新增 `test` 块 +
      `src/__tests__/setup.js` 浏览器 API 兜底），`@/services/api` 全量打桩；
      前端用例 29 → **47 全绿**（4.4s），`npm run build` 通过。
      踩坑记录：`vi.mock` 工厂若用 Proxy 且 `get('then')` 返回函数，模块会被当 thenable，
      `await import()` 永不返回（表现为全部用例 20s 超时）——mock 必须显式列出导出名。
- [x] ✅ 2026-09-17（commit `96d8453`）**TD-15 聊天页缺远程模型入口（用户 2026-09-17 反馈）**
      根因：`ModelService.getModels` 只把**已配置的云端模型**并入 `available_models`
      （`ModelService.java:86-108`），本机 `data/cloud_providers.json` 为 `{ "providers": [] }`，
      所以下拉里只剩本地模型，且所有条目共用 `fa-cube` 图标、无任何引导。
      落地：后端 `getModels` 新增 `cloud_model_name`（配置的远程模型名，与用户当前是否在云端模式
      无关）；前端 store 增加 `cloudProvider` / `cloudModelName`；聊天页桌面下拉与移动端抽屉都加了
      「添加/管理远程模型」入口（跳 `/admin/models`）、远程条目云图标、未配置时的引导行；
      `chatModelEntry.test.js` 2 用例覆盖两种状态。
- [x] ✅ 2026-09-17（commit `96d8453`）**TD-07 无测试引用的控制器补测**
      落地：`ImageControllerTest` 7 用例（空 prompt 400、服务缺失降级、默认参数透传、
      未知文件 404、已知文件返回 PNG + 长缓存头、非法 workflow 400、进度快照兜底）。
      后端全量 650 → **658 用例全绿**；`npm run test` 前端 47 → **49 用例全绿**。
      另一处 `SpaController` 经查为死代码，处置需决策 → 转 TD-16。
- [x] ✅ 2026-09-17（commit `ffacd6d`）**TD-08 eval 质量门常态化**
      落地：新增 `EvalTrendReporter`（把 `target/eval-results/eval-*.jsonl` 汇总成 markdown
      趋势：历次运行用例数/ok/error/平均分/最低分/低于 7 分用例数 + 最近一次**全量**运行逐用例
      得分与环比；自动跳过增量调试运行），`EvalSuite` 跑完自动刷新，另有离线契约测试
      `EvalTrendReporterTest`（3 用例，不需要 Ollama）；干净检出/CI 上直接跳过。
      一键命令：`cd backend/web && ./mvnw.cmd test -Dtest=EvalTrendReporterTest`（只汇总不推理）；
      跑评测：`./mvnw.cmd -Peval test`，质量门 `-Deval.min-score=7`，单用例调试 `-Deval.cases=id1,id2`。
      实测数据（`docs/eval-trend.md`）：6 次历史运行已被还原成趋势，2026-08-25 全量 36 例
      平均 7.22 / 最低 0 / 12 例低于 7 分 → **质量门 7 目前必然失败**，低分项治理另开 TD-17。

</details>

<details>
<summary>2026-09-17 会话收尾记录（点击展开）</summary>

> 来源：2026-09-17 全仓技术债扫描（代码度量 / 文档漂移核查 / CI 与测试门禁核查 / 依赖与密钥面扫描）。
> 用户要求：整理优先级 → 记录待办 → 逐项推进。
> **本节是当前唯一活跃的待办队列**，其余章节为历史台账；完成一项就地勾选并注明 commit。
>
> 基线（2026-09-17 实测）：后端 `mvn test` **650 用例 / 117 个测试类全绿**（0 失败 0 跳过，~50s）；
> 前端 vitest 29 用例 / 7 个测试文件；E2E `tests/e2e-java` 19 个类（手动触发）；
> 内置工具 22 个 bean；密钥面扫描（sk- / AKIA / ghp_ 模式）0 命中，`.env*` 已 ignore。

> **2026-09-17 会话收尾**（当日最后一次更新）：本轮共 9 个 commit，落地 6 项、新记 3 项。
> - 已完成：TD-01 / TD-02 / TD-03（文档与仓库卫生）、TD-06（前端 18 视图冒烟）、
>   TD-07（ImageController 契约测试）、TD-08（eval 趋势汇总 + 入库快照）、
>   TD-15（聊天页远程模型入口）。
> - 当日结束时基线：后端 **661 用例全绿**、前端 **49 用例全绿** + build 通过。
> - **等外部条件的 3 项（不阻塞其他工作）**：
>   ① TD-05 已实现，等 push 后 CI 首次运行确认（绿了才勾选）；
>   ② TD-16 等 owner 决策「删 SpaController」还是「让后端真能直出 SPA」——未决策前不要动；
>   ③ TD-00 等 Telegram Bot Token / chat_id 凭证。
> - **下一批建议顺序**：TD-17 第一步（eval 状态隔离，改动小、让后续评测结论可信）→ TD-04
>   （TODOS 拆档）→ TD-09（AgentOrchestrator 拆分）→ TD-10（模型能力注册表）；
>   TD-13/TD-14 可随时插入（互相独立）。
> - 当日新增/更新的持久产物：`docs/eval-trend.md`（eval 趋势快照）、
>   `tests/qa/`（几何审计脚本）、`backend/web/src/test/java/.../eval/EvalTrendReporter.java`。

</details>

---

## 历史台账

2026-08-30 及更早的全部章节已迁出：

- [2026-06 ~ 2026-08 历史台账](docs/todos-archive/2026-06-to-2026-08-history.md)（2194 行原样搬移）


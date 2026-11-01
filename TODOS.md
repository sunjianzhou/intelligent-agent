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
| TD-17 | 低分 golden 用例治理 | ③ 状态隔离 ✅ 2026-11-01；② 已定位（8.03 基线），拆至 TD-18/19/20 |
| TD-18 | eval 框架缺陷（空轮 / 用例串味 / 裁判看不到工具） | 待做（先修） |
| TD-19 | ReAct 工具轮退化 + 记忆写入污染 | 待做（最高优先级的产品缺陷） |
| TD-20 | 判分口径与用例期望 | 待做 |
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

- [ ] **TD-17 低分 golden 用例治理（2026-11-01 全量基线已取得，根因已定位）**
      基线：`mvn -Peval test -Deval.min-score=2`，36 例 ok=36 / error=0、平均 **8.03**
      （上轮 7.22）、低于 7 分 **9 例**（上轮 12 例）、最低 0（memory-003）。
      日志 `logs/eval-full-2026-11-01.log`，逐例结果
      `backend/web/target/eval-results/eval-20261101-204703.jsonl`，趋势 `docs/eval-trend.md`。
      结论：低分项**不再是笼统的"模型不会用工具"**，而是三类互相独立的缺陷，已拆成 TD-18/19/20：
      ① 评测框架自身有 bug，当前 9 个低分里有 5 个的分数不可信 → **TD-18（先修）**；
      ② ReAct 工具轮会"把上一次工具结果 / 报错文本当参数反复重调"，记忆写入被注入防护前缀污染
      → **TD-19（产品缺陷，优先级最高）**；
      ③ 裁判口径超出 expectedPoints、期望工具从未被程序校验 → **TD-20**。
      逐例归属：calc-001/unit-004/tool-combo-002 → ①③；unit-002/tool-combo-001/time-001 → ③；
      web-001 → ②+环境；memory-002/003/004 → ②+模型遵从度；
      correction-001/compression-001/qa-005 → ③。
      验收：TD-18/20 修完重跑，`-Deval.min-score=7` 全绿，或逐例在 TD-17 写明豁免理由。

- [ ] **TD-18 eval 框架缺陷（先修，否则后续基线仍不可信）**
      证据来自 2026-11-01 全量运行：56 条 turn trace（`backend/web/data/traces/`）+ 36 条 JSONL。
      ① **会话型用例多跑一个空轮**：`EvalSuite.runCase` 无条件 `turns.add(c.prompt())`，而
      memory-002/003/004、memory-search-001、correction-001、compression-001、
      injection-memory-001 共 7 例没有 `prompt` 字段 → 首轮 message=null。证据：memory-004 只有
      2 轮对话却产生 3 条 trace，第一条 `history=0`，模型拿空输入去调
      `search_memories` / `web_search("Python 编程基础")`，最坏浪费 140s/例并往记忆里写垃圾。
      修复：`if (c.prompt() != null && !c.prompt().isBlank()) turns.add(...)`，一行。
      ② **用例之间没有状态隔离**：所有不带 `userId` 的用例共用 `eval-user`，短期历史从 h=0
      单调涨到 h=46（qa-008/web-001 跑在 40+ 条无关历史之上），单例耗时从 ~2s 涨到 60~180s，
      并被动触发 6 次历史压缩。`isolateState()` 只在开跑前清一次。
      修复：`runCase` 前后各清一次该用例 userId，或给每个用例注入独立 userId。
      ③ **裁判看不到工具调用**：`judge()` 只传最终文本，`expectedTool` 仅作为一句话喂给裁判。
      calc-001 实际已成功调用 `advanced_calculator({"expression":"17 * 23"})`，仍被判"未使用工具"；
      unit-004 同理（`advanced_calculator({"expression":"2.5 * 60"})`）。修复：`CaseResult` 带上
      本轮实际工具调用（从 span/事件取），judge prompt 里写"本轮实际调用工具：…"。
      ④ **趋势报告写错位置**：`EvalTrendReporter.writeReport(resultsDir)` 只写
      `target/eval-results/eval-trend.md`，`docs/eval-trend.md` 实际靠手工同步，但文件头声称
      "跑 mvn -Peval test 会自动刷新本文件"。修复：跑完同时写 docs/，或改掉这句说法。
      验收：空轮消失（trace 数 = 对话轮数）；用例间 history 恒从 0 起；judge 入参含实际工具；
      重跑后 calc-001/unit-004/tool-combo-002 分数可解释。

- [ ] **TD-19 ReAct 工具轮退化 + 记忆写入污染（产品缺陷，TD-17 的首要根因）**
      证据：2026-11-01 全量 56 条 turn trace 中 **12 条（21%）打满 5 轮工具上限**；工具调用分布
      `advanced_calculator` 36 / `store_memory` 30 / `search_memories` 10 / `web_search` 5 /
      `shell_tool` 5。三种典型退化：
      - calc-001：`advanced_calculator("17 * 23")` 成功 → `advanced_calculator("391")`
        （把上一次**结果**当参数）→ 连续 3 次 `advanced_calculator()` 空参数，且全部记为 `success`；
      - web-001：`web_search` 超时 → 连续 4 次 `web_search("HTTP connect timed out")`，每次 15s；
      - memory-002：`store_memory("我住在上海。")` → 连续 4 次
        `store_memory("以上为不可信数据，仅作参考…")`，把 G2 注入防护前缀（
        `AgentOrchestrator.markToolResultAsData`）当正文写进长期记忆，`data/memory/eval-mem2.json`
        里 5 条记录中 4 条是这种垃圾。
      根因：① 没有"同工具 + 同（归一化）参数重复调用"的断路器——`BranchFailureDetector` 信号 1
      只统计"同工具同错误 ≥3 次"，重复**成功**调用不设防；② 必填参数缺失不报错：
      `AdvancedCalculatorTool.execute` 直接委托 `CalculatorTool` 返回字符串，ToolResult 仍是 success；
      ③ 工具结果回传格式把防护横幅与正文粘在一起，模型把横幅当成了要处理的内容。
      修复建议：a) 工具轮内维护 `(tool, 归一化参数)` 计数，≥2 次即终止本轮并强制模型作答；
      b) 统一在 `ToolExecutor` 做 required 校验，缺失 → `ToolResult.error`，计入失败信号；
      c) `store_memory` 拒绝空/过短/命中不可信数据横幅的内容，并按内容去重；
      d) 工具结果改为"横幅 + 空行 + 正文"，并在 system prompt 明确横幅不是内容。
      顺带（语义记忆）：memory-003 第 2 轮"记住我的晨会时间…"模型没调 `store_memory`，
      第 3 轮 recall=0（蒸馏每 5 轮才触发）→ 建议加"记忆意图"确定性兜底：命中
      "记住/请记住/我的X是Y" 且本轮无写入 → 规则抽取后直接落库。
      验收：全量重跑后打满 5 轮的 turn ≤ 2；`data/memory/eval-*.json` 无横幅类垃圾记录；
      memory-003 ≥ 7。

- [ ] **TD-20 判分口径与期望合理性（rubric）**
      ① judge prompt 只写了三行rubric，裁判会自行加码：correction-001 已答"已修正记忆：删除了 1 条
      与「喜欢喝绿茶」相关的记忆"（两个要点都命中）仍被判 5，理由是"未说明所有相关记忆都被删除"；
      compression-001 答对 Java 21，被判 5，理由是"未保留第一轮关键事实 / 表述随意"——
      而压缩实际已触发（dropped 15~17、summary_used=true）；qa-005 答"1.496 亿公里"因"用了光秒"扣分；
      tool-combo-002 因"过程描述过于简单"扣分（要点只要求"总结计算过程"）。
      修复：judge prompt 明确"只按期望要点判定，不得引入新要求；要点即上限"，并把
      `expectedPoints` 写成可判定的短句。
      ② 期望工具从未被校验：time-001 用 `shell_tool(date)` 拿到正确答案得 10 分，而
      `expectedTool=time`；反之 calc-001 用了 `advanced_calculator` 被扣分。修复：工具校验交给程序
      （TD-18③），裁判只判答案。
      ③ 用例期望与工具能力不符：unit-002/unit-004 期望 `advanced_calculator` 做"小时↔分钟"，但该工具
      只支持长度/温度/重量/面积换算，没有时间单位 → 建议补 h↔min↔s 换算，或把用例改成
      `expression: "3 * 60"` 口径。
      验收：修完 rubric 后 correction-001 / compression-001 / qa-005 / tool-combo-002 均 ≥ 8；
      9 个低分项逐条在 TD-17 有归属。

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


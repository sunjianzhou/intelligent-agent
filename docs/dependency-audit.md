# 依赖陈旧例行检查（TD-14）

> 只读体检。**不自动升级**——升级动作一律单独开项、单独跑全量测试。
> 节奏：每季度人工执行一次（或触发 `dependency-report` workflow）。每次把结果追加到本文「检查记录」。

## 可复现命令

后端（`backend/web/`，需 JDK 21）：

```powershell
$env:JAVA_HOME = "<JDK21 路径>"; $env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
cd backend/web
.\mvnw.cmd versions:display-dependency-updates -DprocessDependencyManagement=true
```

前端（`frontend/`）：

```bash
npm outdated --json    # 有旧版本时退出码为 1，属正常
npm audit --omit=dev   # 安全面（可选）
```

CI 里对应的只读报告：`.github/workflows/dependency-report.yml`（手动触发 + 每月 1 日），
产物为 `backend-dependency-updates.txt` / `frontend-outdated.json`，不修改仓库。

## 判读规则

| 情况 | 处理 |
|------|------|
| 由 Spring Boot BOM 管理的依赖（jackson / commons-lang3 / httpclient5 / slf4j / lombok / reactor / Boot starters） | **不手工在 pom 写版本号**，跟随 Boot 升级一起动 |
| `-RC*` / `-alpha*` / `-beta*` / `-M*` | 不跟进，等 GA |
| PATCH / MINOR（语义化版本） | 可单独升级，跑全量测试即可 |
| MAJOR | 单独开项，评估 API 变更面 + 回归范围 |
| `pom.xml` 中显式钉住版本的依赖 | 逐个核对下方「显式依赖」表 |

## 检查记录

### 2026-11-01（首次，TD-14 建立）

后端 `mvn versions:display-dependency-updates`：BUILD SUCCESS，3m44s。

**显式钉住版本的直接依赖**：

| 依赖 | 当前 | 可升 | 建议 |
|------|------|------|------|
| `org.jsoup:jsoup` | 1.23.2 | — | ✅ 本次已从 1.17.2 升级（TD-13） |
| `org.apache.pdfbox:pdfbox` | 3.0.8 | — | 保持 |
| `com.mysql:mysql-connector-j` | 8.4.0 | 26.10.0 | ⏸ MAJOR + 版本号体系变更（8.x → 日历版），需单独验证 DatabaseTool 连接/查询/元数据读取 |
| `com.larksuite.oapi:oapi-sdk` | 2.4.19 | 2.8.7 | ⏸ MINOR 但跨 4 个小版本，飞书长连接协议敏感；升级必须跑真机 `FeishuWebSocketClient` 连通验证 |
| `io.jsonwebtoken:jjwt-*` | 0.12.7 | 0.13.0 | ⏸ 认证核心（P1 风险），0.13 有 API 调整；单独排期 |
| `com.h2database:h2`（test） | 2.2.224 | 2.5.252 | 可升（仅测试用），跟随下次测试依赖整理 |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 2.9.0 | 3.2.0 | ⏸ 3.x 面向 Spring Boot 4，Boot 3.5 下保持 2.9.0（pom 已注释说明） |
| `commons-io:commons-io` | 2.11.0 | 2.22.0 | 可升（2021 年的 2.11 已很旧，属纯工具类） |
| `com.squareup.okhttp3:mockwebserver`（test） | 4.12.0 | 5.5.1 | ⏸ 4→5 跨大版本（OkHttp 5）；测试专用，可单独做 |
| `net.objecthunter:exp4j` | 0.4.8 | — | 保持（上游停更） |

**Boot BOM 管理、本次不动的条目**（仅记录）：jackson 2.21.4→2.22.4、
commons-lang3 3.17.0→3.21.0、httpclient5 5.5.2→5.7-beta1（beta，跳过）、
slf4j-api 2.0.18→2.1.0-alpha1（alpha，跳过）、lombok 1.18.46→1.18.48、
reactor 3.7.19→3.9.0-RC1（RC，跳过）、Spring Boot 3.5.16→4.2.0-RC1（RC，跳过）。

**前端 `npm outdated`**（15 个）：

| 包 | 当前 | wanted | latest | 建议 |
|----|------|--------|--------|------|
| `dompurify` | 3.4.13 | 3.4.17 | 3.4.17 | 🔼 优先升（XSS 相关，patch） |
| `axios` | 1.19.0 | 1.21.0 | 1.21.0 | 🔼 可升（minor） |
| `vue` | 3.5.33 | 3.5.44 | 3.5.44 | 🔼 可升（patch） |
| `marked` | 18.0.3 | 18.1.2 | 18.1.2 | 🔼 可升（minor，渲染行为回归靠前端测试） |
| `highlight.js` | 11.11.1 | 11.12.0 | 11.12.0 | 可升（minor） |
| `sass` | 1.99.0 | 1.107.1 | 1.107.1 | 可升（构建期） |
| `element-plus` | 2.13.7 | 2.14.7 | 2.14.7 | ⚠ 可升但 UI 组件行为需目视回归 |
| `@fortawesome/fontawesome-free` | 6.5.2 | 6.7.2 | 7.3.1 | ⚠ 升到 6.7.2 安全；7.x 是 MAJOR（图标命名/API 变更） |
| `@vitejs/plugin-vue` | 6.0.8 | 6.0.10 | 6.0.10 | 可升（patch） |
| `jsdom` / `fake-indexeddb` | 30.1.0 / 6.2.5 | 30.1.3 / 6.2.7 | 同 | 可升（测试用 patch） |
| `vite` | 7.3.6 | 7.3.7 | 8.3.5 | ⏸ 只跟 7.x patch；8.x MAJOR 单独排期 |
| `vitest` | 3.2.7 | 3.2.7 | 5.0.5 | ⏸ MAJOR，单独排期 |
| `pinia` | 3.0.4 | 3.0.4 | 4.1.0 | ⏸ MAJOR（store API 变更面大） |
| `vue-router` | 4.6.4 | 4.6.4 | 5.4.0 | ⏸ MAJOR |

**下一批可安全升级（未执行，待单独立项）**：
后端 `commons-io 2.11.0 → 2.22.0`、`h2 2.2.224 → 2.5.252`；
前端 `dompurify`、`axios`、`vue`、`marked`、`highlight.js`、`sass`、
`@vitejs/plugin-vue`、`jsdom`、`fake-indexeddb`（均为同 MAJOR 内的 patch/minor）。

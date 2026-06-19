# Frontend 深色科技感改版 · Phase 2（业务页令牌化）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Phase 1 落地的设计令牌（`frontend/src/styles/main.css` 的 `--color-*` `--space-*` `--radius-*` `--shadow-*` `--text-*` `--content-max-width`）铺到 6 个最常用的业务页面，替换页面 `<style scoped>` 里硬编码的颜色/间距/圆角/阴影数值，让这些页面和已经令牌化的 Header/Sidebar/App 外壳视觉统一。

**Architecture:** 对每个页面文件做"硬编码值 → 最接近的设计令牌"替换，只动 `<style scoped>` 块里的数值，不动 `<template>`/`<script>`。替换基于下面的主映射表；遇到映射表里没有覆盖、或替换后视觉效果可能明显变化的值，**保留原值并在报告里列出**，不要自行发明新令牌或猜测映射。

**Tech Stack:** Vue 3 `<style scoped>`，CSS 自定义属性。不引入新依赖。

## Global Constraints

- 不引入新的 npm 依赖
- 不修改任何 `<template>` 或 `<script>` 代码、不改交互逻辑、不改 Pinia store / WebSocket 逻辑
- 每个任务只修改它对应的那一个 `.vue` 文件
- 每个任务完成后 `cd frontend && npm run build` 必须 exit 0
- 没有浏览器可用，"手动浏览器验证"步骤统一推迟给人工控制者（已知预期，不算 concern）
- **主映射表（颜色）**——出现下列十六进制色值（或非常接近的同色系值）时替换为对应令牌，大小写/简写（如 `#fff` vs `#ffffff`）都算匹配：
  | 原值 | 替换为 |
  |---|---|
  | `#667eea`, `#5b7cfa`, `#5a6fd6`（主紫蓝，含 hover 变体） | `var(--color-primary)`（hover 态用 `var(--color-primary-hover)`） |
  | `#4fc3a1`, `#43a047` 用作"在线/激活/强调"语义时 | `var(--color-accent)` |
  | `white`, `#fff`, `#ffffff` 作为卡片/面板背景 | `var(--color-surface)` |
  | `#f8f9fa`, `#fafbfc`, `#f5f5f5`, `#fafafa` 作为页面/区块背景 | `var(--color-bg)` |
  | `#f0f1ff`, `#f0f2ff` 作为高亮/选中背景 | `var(--color-surface-raised)` |
  | `#e0e3e8`, `#e8eaed`, `#e1e5e9`, `#f0f2f5`, `#eee`, `#f0f0f0` 作为边框 | `var(--color-border)` |
  | `#333`, `#222`, `#1f2430` 类深色正文 | `var(--color-text)` |
  | `#555`, `#666` 类次级文字 | `var(--color-text-secondary)` |
  | `#888`, `#999`, `#aaa`, `#bbb`, `#ccc` 类弱文字/占位 | `var(--color-text-muted)` |
  | `#4caf50`, `#2e7d32`（成功语义） | `var(--color-success)` |
  | `#f44336`, `#c62828`, `#ef4444`, `#ff6b6b`（危险语义） | `var(--color-danger)` |
  | `#f57c00`, `#ffc107`（警告语义） | `var(--color-warn)` |

  **主映射表（间距/圆角/阴影，仅在数值与下表完全相等时替换，不做近似四舍五入）：**
  | 原值 | 替换为 |
  |---|---|
  | `4px` | `var(--space-1)` |
  | `8px` | `var(--space-2)` |
  | `12px` | `var(--space-3)` |
  | `16px` | `var(--space-4)` |
  | `24px` | `var(--space-5)` |
  | `32px` | `var(--space-6)` |
  | `border-radius: 6px` / `7px` / `8px` | `var(--radius-sm)` |
  | `border-radius: 10px` / `12px` | `var(--radius-md)` |
  | `border-radius: 16px` | `var(--radius-lg)` |
  | 形如 `box-shadow: 0 Npx Mpx rgba(0,0,0,0.0x)` 的轻量卡片阴影 | `var(--shadow-sm)` |

  其它字号、未列出的间距数值（如 `14px`、`20px`、`0.5px` 边框）、以及任何不确定该映射到哪个令牌的值，**保持原样不动**，并在报告里列出"未替换的硬编码值"清单供人工复查。
- 如果页面顶层容器（如 `.xxx-view { padding: ...; max-width: NNNpx; ... }`）有固定 `max-width`，且明显是用于限制整页内容宽度（不是表单/卡片内部的局部宽度限制），把它换成 `var(--content-max-width)`（参考 `RoleEditorView.vue`/`ModelView.vue` 已经做过的修复）；如果不确定该不该改，保留原值并在报告里说明。

---

## File Structure

本计划只修改以下 6 个文件的 `<style scoped>` 块，不创建新文件：

| 文件 | 说明 |
|---|---|
| `frontend/src/views/ChatView.vue` | 聊天主页面（最高频） |
| `frontend/src/views/ProjectView.vue` | 项目文件页 |
| `frontend/src/views/MemoryView.vue` | 记忆管理页 |
| `frontend/src/views/KnowledgeView.vue` | 知识库管理页 |
| `frontend/src/views/ImageView.vue` | 图片生成页 |
| `frontend/src/views/RoleEditorView.vue` | 角色配置页（已修过 max-width，本任务补做颜色/间距令牌化） |

---

### Task 1: ChatView.vue 令牌化

**Files:**
- Modify: `frontend/src/views/ChatView.vue`

**Interfaces:**
- Consumes：`frontend/src/styles/main.css` 中 Phase 1 已定义的全部令牌（颜色/间距/圆角/阴影/`--content-max-width`），见本计划 Global Constraints 的主映射表
- Produces：无（叶子任务）

- [ ] **Step 1: 读取文件，定位 `<style scoped>` 块**

打开 `frontend/src/views/ChatView.vue`，只关注 `<style scoped>` 到 `</style>` 之间的内容。

- [ ] **Step 2: 按主映射表替换硬编码值**

把映射表中列出的颜色十六进制值替换为对应的 `var(--color-*)`；把数值完全等于 4/8/12/16/24/32px 的间距替换为对应 `var(--space-*)`；把 6/7/8px、10/12px、16px 的 `border-radius` 替换为对应 `var(--radius-*)`；把轻量卡片阴影替换为 `var(--shadow-sm)`。不在表中的值、或你不确定映射是否准确的值，保留原样。

如果文件里有形如 `.chat-view { ...; max-width: ...px; ... }` 这种限制整页宽度的顶层容器规则，按 Global Constraints 最后一条处理。

- [ ] **Step 3: 验证 build 通过**

Run: `cd frontend && npm run build`
Expected: exit code 0。

- [ ] **Step 4: Commit**

```bash
cd frontend
git add src/views/ChatView.vue
git commit -m "feat(frontend): tokenize ChatView colors/spacing/radius"
```

在报告里列出：替换了多少处、保留未替换的硬编码值清单（如果有）。

---

### Task 2: ProjectView.vue 令牌化

**Files:**
- Modify: `frontend/src/views/ProjectView.vue`

**Interfaces:**
- Consumes：同 Task 1
- Produces：无

- [ ] **Step 1-4：与 Task 1 相同的流程，作用于 `frontend/src/views/ProjectView.vue`**

提交信息：`feat(frontend): tokenize ProjectView colors/spacing/radius`

---

### Task 3: MemoryView.vue 令牌化

**Files:**
- Modify: `frontend/src/views/MemoryView.vue`

**Interfaces:**
- Consumes：同 Task 1
- Produces：无

- [ ] **Step 1-4：与 Task 1 相同的流程，作用于 `frontend/src/views/MemoryView.vue`**

提交信息：`feat(frontend): tokenize MemoryView colors/spacing/radius`

---

### Task 4: KnowledgeView.vue 令牌化

**Files:**
- Modify: `frontend/src/views/KnowledgeView.vue`

**Interfaces:**
- Consumes：同 Task 1
- Produces：无

- [ ] **Step 1-4：与 Task 1 相同的流程，作用于 `frontend/src/views/KnowledgeView.vue`**

提交信息：`feat(frontend): tokenize KnowledgeView colors/spacing/radius`

---

### Task 5: ImageView.vue 令牌化

**Files:**
- Modify: `frontend/src/views/ImageView.vue`

**Interfaces:**
- Consumes：同 Task 1
- Produces：无

- [ ] **Step 1-4：与 Task 1 相同的流程，作用于 `frontend/src/views/ImageView.vue`**

提交信息：`feat(frontend): tokenize ImageView colors/spacing/radius`

---

### Task 6: RoleEditorView.vue 颜色/间距令牌化（补做）

**Files:**
- Modify: `frontend/src/views/RoleEditorView.vue`

**Interfaces:**
- Consumes：同 Task 1。注意：该文件的 `.role-editor` 顶层 `max-width` 已经在之前的修复中改成了 `var(--content-max-width)`，本任务**不要**再改这一行，只处理颜色/间距/圆角/阴影
- Produces：无

- [ ] **Step 1-4：与 Task 1 相同的流程，作用于 `frontend/src/views/RoleEditorView.vue`，跳过已经令牌化的 `max-width` 那一行**

提交信息：`feat(frontend): tokenize RoleEditorView colors/spacing/radius`

---

## Self-Review Notes

- **Spec coverage**：覆盖设计文档第 4 节"页面铺开顺序"步骤 3 中列出的全部 6 个业务页文件。后台管理页（步骤 4）与"其余"页面（步骤 5）是后续的 Phase 3 / Phase 4 计划，不在本计划范围。
- **Placeholder scan**：每个任务的替换规则、文件路径、commit message 均为具体、可执行的内容；映射表是完整的查表式规则,不存在"自行判断添加适当样式"这类模糊指令。
- **一致性**：6 个任务引用的令牌名称与 Phase 1（`docs/superpowers/plans/2026-06-19-frontend-tech-redesign-phase1.md`）中定义的完全一致，未发明新令牌名。

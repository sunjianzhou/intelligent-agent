# TODOS

这份文件记录已明确但暂未纳入当前 PR 的工作项。每一条都包含足够上下文，任何人拿起来都能知道从哪里开始。

---

## TODO-1: HTTPS/TLS — 生产环境全程 HTTP 明文

**什么**: 当前整个栈（前端 → Java → Python）全程 HTTP。在网络传输级别，Token 和聊天内容仍为明文。

**为什么**: Token + 聊天内容明文传输是 OWASP A02:2021 加密失效风险。若部署到内网之外或多设备访问，必须解决。

**如何实现**: docker-compose 前加一层 Nginx 反向代理，配置 Let's Encrypt 自动证书。Java/Python 后端无需改动，Nginx 终结 TLS。

**当前状态**: 项目属于本地优先开发阶段，暂不影响开发体验。

---

## TODO-12: 性能优化剩余项（⏸️ 待触发条件）

以下项目已确认可做，但当前规模不值得操作，待对应触发条件出现再处理：

4. **L1 响应缓存锁优化** — `agent/core/agent.py:108-109`；需 `inference_concurrency` 调高后出现热点再做。
5. **Java 侧 token 批量转发** — 需先有延迟抖动证据再做。
8. **Scheduler 轮询改事件驱动** — 任务量小时无所谓；调度器历史上多次出现并发/绑定 bug，改动有风险，待任务量真正增长再做。

> ChromaDB 迁移至 Docker 具名卷已于 2026-06-09 完成，具名卷为 `intelligent_agent_agent_chroma_data` / `intelligent_agent_agent_chroma_data_longterm`。
> 中间无前缀卷 `agent_chroma_data`/`agent_chroma_data_longterm` 可按需 `docker volume rm` 清理。

---

## TODO-16: Java 死代码清理（LOW）

**什么**: `RoleController.java` 改为纯代理层后，以下两个文件成为死代码，没有任何代码路径引用它们：
- `backend/.../service/RoleService.java`
- `backend/.../dto/role/RoleConfigDto.java`

**如何实现**: 直接删除两个文件。删前先 grep 确认无引用：
```bash
grep -r "RoleService\|RoleConfigDto" backend/web/src/main/java/
```

**文件**: `backend/.../service/RoleService.java`，`backend/.../dto/role/RoleConfigDto.java`

**代价**: Human ~5min / CC ~2min

---

## TODO-17: `list_roles` 接口改为只返回卡片信息（LOW）

**什么**: `agent/api/roles_router.py:77-85` 中 `GET /api/roles` 对每个 role_id 都调用 `rm.load_role()` 即完整加载 JSON 文件。角色列表页面只需要展示名称、头像、签名，无需完整配置。

**为什么**: 角色数量增多后，列表接口将做 O(N) 全量磁盘读取。现有 `GET /{role_id}/card` 端点已返回轻量 RoleCard。

**如何实现**: `list_roles` 端点改为只调 `rm.load_role(role_id)` 取 `.role_card` 而非整个 config。或新增 `RoleManager.list_role_cards()` 方法，只读 JSON 文件中的 `role_card` 字段（避免 Pydantic 完整 validate）。

**文件**: `agent/api/roles_router.py`，`agent/personas/role_manager.py`

**代价**: Human ~20min / CC ~10min

---

## TODO-18: 两套角色体系的合并方向（MEDIUM，需先设计决策）

**什么**: 系统中存在两套并行的角色体系：
1. **旧体系**：`agent/personas/*.md` 文件 → `personas_router.py` → `PersonasView.vue`（简单文本 prompt 模板）
2. **新体系**：`agent/data/roles/*.json` + ChromaDB → `roles_router.py` → `RoleEditorView.vue`（完整角色配置）

当前优先级链：JSON role > .md persona > default。两套体系在 UI 上各自独立，用户可能困惑"角色"和"人设"的区别。

**选项**:
- **A（推荐）**: 逐步废弃 .md 体系，给 PersonasView 加一个"迁移向导"，将现有 .md personas 一键转为 JSON roles（name + language_style → CoreIdentity，文件内容 → role_card.signature）。完成后隐藏 PersonasView 入口。
- **B**: 保留两套体系，改善 UI 说明（".md 角色是简单模板，角色配置是完整人设"），不做代码合并。

**当前状态**: 设计决策未定，代码暂无需改动。

---

## TODO-19: 角色相关测试补全（HIGH）

**什么**: 本次评审发现角色体系（`roles_router.py`、`role_manager.py`、`prompt_builder.py`、`role_models.py`）完全没有测试覆盖。

**覆盖路径**:

```
角色 CRUD 路径（未测）
├── POST /api/roles → create_role → RoleManager.save_role → JSON 写盘
├── GET /api/roles/{id} → load_role → JSON 读盘 + Pydantic validate
├── PUT /api/roles/{id} → update_role → 覆盖写盘
├── PATCH /api/roles/{id} → patch_role → _deep_merge → Pydantic validate
└── DELETE /api/roles/{id} → delete_role → 删 JSON + 删 ChromaDB collections

角色激活路径（未测）
├── POST /api/roles/activate → _user_active_roles[user_id] = role_id
├── GET /api/roles/activate → 返回当前激活
└── DELETE /api/roles/activate → pop

上下文构建路径（未测，最关键）
├── get_role_context → load_role + 3x _semantic_search
│   └── PromptBuilder.build_system_prompt → 9 个 section 构建器
└── _get_user_role_persona_content → fastapi_app.py → 注入 chat 请求
```

**需新增**:
- `agent/tests/test_role_manager.py`：RoleManager CRUD + add_memory + get_role_context（mock ChromaDB）
- `agent/tests/test_prompt_builder.py`：build_system_prompt 的所有 9 个 section + 边界（空上下文、无 redlines 等）
- `agent/tests/test_roles_endpoints.py`：全部端点 happy path + 404 / 400 错误路径（`httpx.ASGITransport` + mock RoleManager）

**文件**: 新建 `agent/tests/test_role_manager.py`，`agent/tests/test_prompt_builder.py`，`agent/tests/test_roles_endpoints.py`

**代价**: Human ~2h / CC ~30min

---

## TODO-20: Docker 中间无前缀卷清理（LOW）

**什么**: ChromaDB 迁移过程（2026-06-09）创建了两个无项目前缀的中间卷：`agent_chroma_data` 和 `agent_chroma_data_longterm`。数据已迁移到正式卷（`intelligent_agent_agent_chroma_data` / `intelligent_agent_agent_chroma_data_longterm`），中间卷空间仍占用。

**如何清理**:
```bash
docker volume rm agent_chroma_data agent_chroma_data_longterm
```

**注意**: 执行前先确认无容器正在挂载这两个卷：
```bash
docker ps -a --filter volume=agent_chroma_data
```

**代价**: Human ~5min / CC ~2min

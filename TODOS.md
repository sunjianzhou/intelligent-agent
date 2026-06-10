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

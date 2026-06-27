# 飞书个人日历/任务 OAuth 授权设计文档

**日期**：2026-06-27  
**状态**：已通过设计评审，待实现  
**关联 TODO**：TODO-85（待写入 TODOS.md）

---

## 1. 背景与目标

现有 `feishu_calendar.py` / `feishu_task.py` 使用 `tenant_access_token`（应用身份），只能访问应用自己创建或被显式共享的日历/任务，**无法读写用户私人日历和待办**。

目标：实现飞书 OAuth 2.0 授权流程，获取 `user_access_token`，使 agent 能够读写用户的个人日历事件和飞书任务（读+写全权限）。

---

## 2. 整体数据流

```
[用户：给我飞书日历授权链接]
         ↓
  GET /api/feishu/oauth/authorize?open_id=ou_xxx
         ↓
  Python 生成 random_state，存入 _pending_states（5 分钟 TTL）
  返回 {auth_url: "https://open.feishu.cn/open-apis/authen/v1/authorize?..."}
         ↓
  用户浏览器打开 auth_url → 飞书授权页 → 点「允许」
         ↓
  飞书重定向 → https://{tunnel域名}/feishu/oauth/callback?code=xxx&state=random_state
         ↓
  Java FeishuOAuthController.oauthCallback() 无鉴权透传
  → Python GET /api/feishu/oauth/callback?code=xxx&state=random_state
         ↓
  Python 验证 state → 查 _pending_states → 取 open_id → 删除条目
  用 code 换 user_access_token + refresh_token
  Fernet 加密后写入 agent/data/feishu_oauth_tokens.json
  返回"授权成功" HTML 页
         ↓
  后续工具调用 get_valid_token(open_id)
  → 提前 5 分钟主动刷新（per-open_id asyncio.Lock 防并发重复刷新）
```

---

## 3. 安全设计

### 3.1 state 防 CSRF

- 授权发起时生成 `state = str(uuid4())`，不直接使用 open_id
- 内存映射：`_pending_states: dict[str, {open_id, created_at}]`，TTL 5 分钟
- 回调时：state 不存在 / 已过期 → 400 拒绝

### 3.2 Fernet 加密存储

- 密钥来源：环境变量 `FEISHU_OAUTH_ENCRYPTION_KEY`（32 字节 base64，一次性生成）
- 加密字段：`access_token`、`refresh_token`（明文存储敏感 token 会导致文件泄露即泄露授权）
- 不加密：`expire_at`、`refresh_expires_at`（时间戳无敏感性）
- 密钥备份策略：与 `.env.docker` 一起备份（建议加密压缩后存放在与代码仓库分开的安全位置）；密钥丢失后已存 token 无法解密，需重新授权

### 3.3 启动强制校验

`fastapi_app.py` lifespan 中：

```python
if settings.feishu_enabled:
    if not settings.feishu_oauth_redirect_uri:
        raise RuntimeError("FEISHU_ENABLED=true 时必须配置 FEISHU_OAUTH_REDIRECT_URI")
    if not settings.feishu_oauth_encryption_key:
        raise RuntimeError("FEISHU_ENABLED=true 时必须配置 FEISHU_OAUTH_ENCRYPTION_KEY")
```

---

## 4. Token 存储结构

文件：`agent/data/feishu_oauth_tokens.json`

```json
{
  "ou_1d2e0c80f6feffa546a1b28664bb39c2": {
    "access_token":       "<fernet-ciphertext>",
    "refresh_token":      "<fernet-ciphertext>",
    "expire_at":          1751234567.0,
    "refresh_expires_at": 1753826567.0
  },
  "ou_另一个用户": {
    "..."
  }
}
```

- `expire_at`：`user_access_token` 过期时间（通常 2 小时）
- `refresh_expires_at`：`refresh_token` 过期时间（= 授权时刻 + 30 天）
- `get_valid_token()` 在刷新前先检查 `refresh_expires_at`；若 refresh_token 也过期，抛出明确异常提示用户重新授权

---

## 5. Token 刷新策略

```python
_refresh_locks: defaultdict[str, asyncio.Lock]  # per open_id

async def get_valid_token(open_id: str) -> str:
    async with _refresh_locks[open_id]:
        data = _load_tokens().get(open_id)
        if not data:
            raise RuntimeError(f"用户 {open_id} 尚未完成飞书 OAuth 授权")

        now = time.time()
        if now > data["refresh_expires_at"]:
            raise RuntimeError(f"用户 {open_id} 的 refresh_token 已过期（30天），需重新授权")

        if data["expire_at"] - now < 300:   # 提前 5 分钟主动刷新
            data = await _do_refresh(data["refresh_token"])
            _save_token(open_id, data)

        return _decrypt(data["access_token"])
```

---

## 6. API 端点

### `GET /api/feishu/oauth/authorize`

参数：`open_id` (string, required)  
返回：`{"auth_url": "https://open.feishu.cn/..."}`  
行为：生成 random state，存 _pending_states，构造授权 URL

### `GET /api/feishu/oauth/callback`

参数：`code`, `state`（飞书回调自动携带）；`error`（用户拒绝时携带）  
返回：HTML 页面（成功/失败提示）  
错误处理：
- `error=access_denied` → HTML 提示"用户拒绝授权"
- state 不存在/过期 → 400 + "state 无效或已过期"
- code 无效（换 token 失败）→ 400 + 飞书原始错误信息
- 成功 → HTML "授权成功，可关闭此页面"

### `GET /api/feishu/oauth/status`

参数：`open_id` (string)，需 JWT 鉴权  
返回：`{"authorized": bool, "expire_at": float|null, "refresh_expires_at": float|null, "refresh_token_expired": bool}`

---

## 7. Feishu OAuth Scope 清单

| scope | 用途 |
|-------|------|
| `contact:user.id:readonly` | 用 code 换 token 后识别用户身份（必须） |
| `calendar:calendar` | 读个人日历事件列表 |
| `calendar:calendar:write` | 创建/更新日历事件 |
| `task:task` | 读飞书任务 |
| `task:task:write` | 创建/完成任务 |

---

## 8. 文件改动清单

### 新增（Python）

| 文件 | 说明 |
|------|------|
| `agent/services/feishu_oauth.py` | Token 管理器（授权 URL / code 换 token / 刷新 / 加解密 / 锁） |
| `agent/api/feishu_oauth_router.py` | 3 个端点（authorize / callback / status） |
| `agent/tools/builtin_tools/feishu_calendar_create.py` | 创建/更新日历事件（user_access_token） |
| `agent/tools/builtin_tools/feishu_task_write.py` | 创建/完成任务（user_access_token） |

### 修改（Python）

| 文件 | 改动 |
|------|------|
| `agent/tools/builtin_tools/feishu_calendar.py` | 优先 `get_valid_token(open_id)`，fallback `tenant_access_token` |
| `agent/tools/builtin_tools/feishu_task.py` | 同上 |
| `agent/core/tool_dispatcher.py` | 注册两个新写工具 |
| `agent/api/fastapi_app.py` | 挂载 feishu_oauth_router；lifespan 加校验 |
| `agent/config/settings.py` | 新增 `feishu_oauth_redirect_uri: str = ""` 和 `feishu_oauth_encryption_key: str = ""` |

### 新增（Java）

| 文件 | 改动 |
|------|------|
| `backend/.../feishu/FeishuOAuthController.java` | 透传 `/feishu/oauth/callback`（无 JWT）和 `/feishu/oauth/status`（有 JWT） |

---

## 9. Java 透传 Controller 设计

```java
@RestController
public class FeishuOAuthController extends AbstractProxyController {

    // 无 JWT 校验：飞书服务器的重定向，没有 JWT
    @GetMapping("/feishu/oauth/callback")
    public ResponseEntity<String> oauthCallback(HttpServletRequest request) {
        String query = request.getQueryString();
        return proxyGetPlain("/api/feishu/oauth/callback"
                             + (query != null ? "?" + query : ""));
    }

    // 有 JWT 校验：前端查询授权状态
    @GetMapping("/feishu/oauth/status")
    public ResponseEntity<?> oauthStatus(@RequestParam String open_id,
                                          HttpServletRequest request) {
        String userId = extractUserId(request);  // from JWT
        return proxyGet("/api/feishu/oauth/status?open_id=" + open_id, userId);
    }

    // 有 JWT 校验：前端获取授权链接
    @GetMapping("/feishu/oauth/authorize")
    public ResponseEntity<?> authorize(@RequestParam String open_id,
                                        HttpServletRequest request) {
        String userId = extractUserId(request);
        return proxyGet("/api/feishu/oauth/authorize?open_id=" + open_id, userId);
    }
}
```

---

## 10. 飞书开放平台后台配置（一次性手动操作）

1. 「权限管理」开通第 7 节 5 个 scope
2. 「安全设置」→「重定向 URL」添加 `https://{tunnel域名}/feishu/oauth/callback`
3. 「版本管理与发布」→ 发布新版本（每次改权限必须重新发布）
4. 发布范围：个人使用选「仅自己可见」或企业内测（无需正式上架）

---

## 11. 环境变量新增

```env
# .env.docker 追加
FEISHU_OAUTH_REDIRECT_URI=https://{tunnel域名}/feishu/oauth/callback
FEISHU_OAUTH_ENCRYPTION_KEY=<Fernet.generate_key() 输出>
```

生成密钥命令：
```bash
python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

---

## 12. 部署流程

```bash
# 1. 生成 Fernet 密钥（只需一次，妥善备份）
python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"

# 2. 写入 .env.docker（FEISHU_OAUTH_REDIRECT_URI + FEISHU_OAUTH_ENCRYPTION_KEY）

# 3. 启动 Cloudflare Tunnel（获取公网域名）
docker compose --profile tunnel up -d

# 4. 将 tunnel 域名填入飞书开放平台「重定向 URL」并发布新版本

# 5. 重建服务
docker compose up -d --build

# 6. 一次性授权（之后 refresh_token 30 天有效，到期前 agent 会告警）
# 在聊天中说"给我飞书日历授权链接"，打开链接点允许即可
```

---

## 13. 不做的事（YAGNI）

- Fernet 密钥轮换 API（手动替换 .env 足够）
- Web 端专属「飞书授权」按钮（聊天入口已够用）
- 多租户（企业版）支持
- WebSocket 实时推送 token 过期事件

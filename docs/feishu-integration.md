# 飞书 IM 接入指南

## 快速开始

### 1. 飞书开放平台配置

1. 进入 [飞书开放平台](https://open.feishu.cn) → 创建**自建应用**
2. 左侧「应用能力」→「机器人」，确认机器人功能已启用
3. 「权限管理」开启以下权限（搜索关键字找到后打开开关）：
   - `im:message:send_as_bot`（发送消息）
   - `im:message`（读取消息，接收事件用）
4. 「事件与回调」→「事件配置」→ 订阅方式选**「使用长连接接收事件」**（无需公网 IP）
5. 点「添加事件」，搜索并添加 `im.message.receive_v1`
6. **关键**：添加完事件后，查看该事件「所需权限」列，逐一点开**「收起未开通权限」**，
   确认所有条目都显示绿色**「已开通」**标记，尤其是：
   - 「读取用户发给机器人的单聊消息」← P2P 单聊收消息必须开通
   - 「获取群组中用户@机器人消息」← 群聊 @ 收消息必须开通
   
   缺任何一条，对应场景的消息不会触发事件，事件日志也不会有任何记录。
7. 「版本管理与发布」→ 创建新版本 → 发布（每次改权限必须重新发布才生效）
8. 可选：开启**消息加密**，记录 Encrypt Key

### 2. 环境变量配置

在 `.env.docker` 中追加：

```env
FEISHU_ENABLED=true
FEISHU_APP_ID=cli_xxxxxxxxxxxxxxxx
FEISHU_APP_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
FEISHU_ENCRYPT_KEY=（可选，开启加密时填写）
FEISHU_VERIFICATION_TOKEN=（可选，卡片回调签名验证用）
FEISHU_BOT_OPEN_ID=（可选，机器人自身 open_id，群聊 @ 判断用，见下文）
```

### 3. 启动

```bash
docker compose up -d
```

查看飞书连接日志：

```bash
docker logs ia-backend | grep "飞书 WS"
```

### 4. 验证

在飞书 App 中向 Bot 发送任意消息，应依次收到：
- ⏳ 思考中...
- AI 回复卡片

## 卡片回调（可选）

若需响应飞书卡片按钮点击，在飞书开放平台配置 HTTP 回调地址：

```
https://your-domain.com/feishu/callback/interactive
```

需要公网可访问（使用 Cloudflare Tunnel：`docker compose --profile tunnel up -d`）。

## 主动推送（Python Agent 侧）

Agent 可通过 `im_message` 工具主动发送飞书消息：

```
用户：任务完成后通知飞书用户 ou_xxxxx
```

Agent 会自动调用 `im_message(receiver_id="ou_xxxxx", msg_type="text", content={"text": "任务已完成"})`

## 群聊行为

- **私聊（p2p）**：正常回复每一条消息。
- **群聊（group）**：默认静默，只有显式 @ 机器人才会回复，避免刷屏。判断是否被 @ 依赖 `FEISHU_BOT_OPEN_ID`——
  填了就精确匹配，留空则退化为"群里有人被 @ 就当作可能 @ 了机器人"的低精度启发式。获取 open_id：飞书开放平台 →
  你的应用 → 「凭证与基础信息」页。

## 主动心跳巡检（可选）

机器人可以按设定节奏自我判断"现在要不要主动联系用户"，而不是只能被动回复。原理：调度器每隔一段时间触发一次
`heartbeat_check` 动作，让模型基于近期对话/长期记忆判断输出 `SILENT`（不打扰）或 `SPEAK: <内容>`（值得主动说），
只有后者才会真正调用 `im_message` 发送；默认 23:00–08:00 安静时段直接跳过，不调用模型。

通过 `POST /api/tasks/create` 创建（`receiver_id` 填你自己的飞书 open_id）：

```json
{
  "name": "飞书心跳巡检",
  "action": "heartbeat_check",
  "args": { "receiver_id": "ou_xxxxxxxxxxxxxxxxxxxxxxxx" },
  "schedule_type": "interval",
  "interval_seconds": 1800
}
```

同一次心跳还会顺带做一次节流的长期记忆归并（每 24 小时一次，写入 `soul/MEMORY.md`），与是否触发主动联系无关，
不需要额外配置。

## 排查：事件日志为空 / 消息收不到

**症状**：开放平台事件日志 0 条，服务端日志只有 ping/pong，发再多消息都没有 `im.message.receive_v1`。

**排查顺序**：

1. **确认订阅方式是长连接**（不是 HTTP 回调）
   - 「事件与回调」→「事件配置」→ 订阅方式应显示「使用长连接接收事件 推荐」
   - 点「验证连接状态」应提示连接成功

2. **确认所需权限全部已开通** ← 最常见原因
   - 点击 `im.message.receive_v1` 行，展开「所需权限」列表
   - 找到「收起未开通权限」按钮并点开，逐项检查
   - **P2P 单聊**：「读取用户发给机器人的单聊消息」必须「已开通」
   - **群聊 @**：「获取群组中用户@机器人消息」必须「已开通」
   - 缺权限 → 「权限管理」搜索关键字开通 → **「版本管理与发布」重新发布**

3. **确认版本已发布**
   - 每次改权限后必须到「版本管理与发布」创建并发布新版本，否则不生效
   - 发布后顶部应显示「当前修改均已发布」

4. **服务端日志确认**
   ```bash
   docker logs ia-backend | grep "飞书 WS"
   # 正常应看到：飞书 WS 客户端启动 → 飞书 WS 已连接
   # 收到消息时会有：receive message, message_type: event ... im.message.receive_v1
   ```

## 已知账号信息（个人企业孙）

| 项目 | 值 |
|------|---|
| App ID | `cli_aab780a758b85bb3` |
| 机器人 open_id | `ou_8788d2ac4f9c24f15bc74ea1859bf9c5` |
| 个人用户 open_id | `ou_1d2e0c80f6feffa546a1b28664bb39c2` |
| Chat ID（P2P） | `oc_2586480151cbbf55dba0d42ef63c2534` |

用户 open_id 来源：首次收到 `im.message.receive_v1` 事件后从 `sender.sender_id.open_id` 字段读取。

## 消息类型参考

| msg_type | 场景 |
|----------|------|
| text | 纯文本 |
| post | 富文本（支持 @人、链接、加粗） |
| interactive | 卡片消息（支持按钮、表格） |
| image | 图片 |
| file | 文件 |
| sticker | 表情包 |
| emoji | Emoji |

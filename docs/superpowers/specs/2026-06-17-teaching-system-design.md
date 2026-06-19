# 教学体系模块设计规范 v1.0

> 版本：v4.7.7.5 + v4.7.8.1  
> 日期：2026-06-17  
> 状态：已确认，待实现

---

## 1. 背景与目标

在现有 Python FastAPI Agent 基础上新增教学体系模块，让糖糖升级为"教学 Agent"：

- 每日定时推送（PWA 主 + 飞书备）
- 自动批改（三题型，对题也解析）
- 错题归档（去重 + 已掌握标记）
- 命令积累（按 topic 分文件）
- 周中/周末差异化节奏

## 2. 永久铁律

| 铁律 | 内容 |
|------|------|
| v4.7.7.5 P1 | 教学模块不引入 PrePushGuard / safety 类 |
| v4.7.7.5 P2 | 推送逻辑不做内容扫描 |
| v4.7.8.1 P1 | 推送主通道：PWA（前端轮询队列） |
| v4.7.8.1 P2 | 推送备通道：飞书 IM，独立 try/except，失败不阻断 P1 |
| v4.6 | 批改对题也输出解析 |
| v4.6.1 | 周中 40% 选择 + 30% 填空 + ≤2 简答；周末仅实操命令 |
| 永久铁律 | 题库答案 ABCD 均匀分散，禁止集中 |

## 3. 产物清单（20 个）

### Python 后端（7 个）

| 文件 | 职责 |
|------|------|
| `agent/teaching/__init__.py` | 包入口 |
| `agent/teaching/question_bank.py` | 题库：各 topic 8-10 题，ABCD 分散 |
| `agent/teaching/daily_plan.py` | 每日计划：周中/周末差异化，4 topic 切换 |
| `agent/teaching/grader.py` | 批改引擎：三题型 + 解析 + 错题归档 |
| `agent/teaching/wrong_book.py` | 错题本：JSON 持久化，去重，已掌握标记 |
| `agent/teaching/command_log.py` | 命令积累：追加写入 commands.md |
| `agent/teaching/pusher.py` | 推送器：从 config.json 加载，注册 4 个 cron |

### API 层（1 个）

| 文件 | 端点 |
|------|------|
| `agent/api/teaching_router.py` | GET /daily-plan, POST /submit, GET/POST /wrong-book |

### 配置文件（1 个）

| 文件 | 说明 |
|------|------|
| `agent/data/scheduler_config.json` | cron 配置，修改时间只改 JSON |

### Memory 文件（4 个）

| 文件 | 说明 |
|------|------|
| `agent/data/memory/k8s-learning/questions.md` | K8s 题库文档 |
| `agent/data/memory/k8s-learning/commands.md` | K8s 命令积累 |
| `agent/data/memory/llm-learning/questions.md` | LLM 题库文档 |
| `agent/data/memory/agent-design/questions.md` | Agent 设计题库文档 |

### 前端（2 个）

| 文件 | 说明 |
|------|------|
| `frontend/src/views/learning/SubmitView.vue` | 答题页：进度条 + 提交 + 批改结果 + 导出 |
| `frontend/src/views/learning/ReviewView.vue` | 错题本页：device/resolved 筛选 + 已掌握标记 |

### 单测（6 个）

| 文件 | 关键用例 |
|------|---------|
| `tests/test_grader.py` | 三题型正确/错误路径；对题也解析；错题入本 |
| `tests/test_daily_plan.py` | 周中题型比例；周末返回命令清单 |
| `tests/test_question_bank.py` | 100 题 ABCD 偏差 < 5% |
| `tests/test_wrong_book.py` | 同 question_id 第二次 add 更新而非新增；resolved 标记 |
| `tests/test_command_log.py` | 多次写入累积不覆盖 |
| `tests/test_pusher.py` | P2 失败不阻断 P1；config.json 加载正确 |

## 4. 核心数据结构

### Question

```python
{
    "id": "k8s-001",
    "topic": "k8s",          # k8s | llm | k8s_review | agent
    "type": "choice",        # choice | fill | short_answer
    "difficulty": 2,         # 1-3
    "text": "题干",
    "options": {"A": "...", "B": "...", "C": "...", "D": "..."},
    "answer": "B",
    "explanation": "解析文本"
}
```

### WrongRecord

```json
{
  "question_id": "k8s-001",
  "topic": "k8s",
  "user_answer": "B",
  "correct_answer": "A",
  "wrong_time": "2026-06-17T13:51:00",
  "last_wrong_time": "2026-06-17T13:51:00",
  "wrong_count": 1,
  "resolved": false,
  "resolved_time": null
}
```

wrong_count ≥ 3 时前端显示红色"高频错题"标签（纯前端判断）。

### scheduler_config.json

```json
{
  "teaching_schedules": [
    {
      "action": "teaching_push_morning",
      "cron": "0 7 * * 1-5",
      "topic": "k8s",
      "label": "K8s 晨读",
      "channel": "dual"
    },
    {
      "action": "teaching_push_midmorning",
      "cron": "0 10 * * 1-5",
      "topic": "llm",
      "label": "LLM 拓展",
      "channel": "dual"
    },
    {
      "action": "teaching_push_review",
      "cron": "40 13 * * 1,3,5",
      "topic": "k8s_review",
      "label": "K8s 复习",
      "channel": "dual"
    },
    {
      "action": "teaching_push_afternoon",
      "cron": "0 15 * * 1-5",
      "topic": "agent",
      "label": "Agent 实战",
      "channel": "dual"
    }
  ]
}
```

## 5. 推送流程（4 个 action 行为完全对称）

```
v3.0 自检：
  1. content 非空
  2. 飞书凭证存在（FEISHU_APP_ID / FEISHU_APP_SECRET）

P1（主）：_push_notification(content)  ← 写入前端轮询队列
P2（备）：FeishuIMTool().execute(...)  ← 独立 try/except，失败仅 warning
```

## 6. API 端点

| 端点 | 方法 | 参数 | 说明 |
|------|------|------|------|
| `/api/teaching/daily-plan` | GET | `topic` | 今日题目 |
| `/api/teaching/submit` | POST | body: Submission | 批改 + 错题归档 |
| `/api/teaching/wrong-book` | GET | `topic`, `device`, `include_resolved` | 查错题本 |
| `/api/teaching/wrong-book/{qid}/resolve` | POST | | 标记已掌握 |
| `/api/teaching/command-log` | GET | `topic` | 查命令积累 |

## 7. 周中/周末节奏（v4.6.1）

| 日期 | 题型分布 | 题数 |
|------|---------|------|
| 周一~周五 | 40% 选择 + 30% 填空 + ≤2 简答 | 5 题 |
| 周六~周日 | 仅实操命令清单 | 0 题 |

## 8. 验收标准

- 7 天推送零漏推（双通道覆盖）
- 批改 100% 准确（三题型）
- 错题本去重 100%（同 question_id 不重复入本）
- 命令积累每日写入
- 周中/周末自动切换
- 6 个单测文件全部通过

## 9. Future（本阶段不做）

- LLM review topic（累计错题 ≥ 30 时启动）
- 题库 version 字段
- 题库扩展到 4 路 topic 独立 review 周期

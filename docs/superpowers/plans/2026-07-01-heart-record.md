# Heart-Record Plan — 心证层落地

> 设计依据：2026-07-01 顶层战略对齐报告（府邸底层能力建设）。
> **状态：✅ 已完成（2026-07-02）** — W1-W3 全部落地，W4 迁移验证首跑 + 文档同步完成。

**Goal:** 新增 soul/heart.md 心证永久档，通过 heart_record 工具供 LLM 在用户说"记住这个"时自动读写，SystemPromptBuilder 在 ③MEMORY 之后注入【心证铁卷】段。

**Architecture:** heart.md 作为 soul/ 可选文件（`SoulLoader.OPTIONAL`），heart_record 注册为 builtin_tool（无环境变量依赖），写入前轮转备份。

---

## 三段设计

### 1. soul/heart.md 文件结构

```markdown
# 心证铁卷

> 此文件记录用户明确标记的永久记忆片段。
> Agent 通过 heart_record 工具读写，用户也可手动编辑。
> git-tracked，跨会话不丢失。

## 主人心证
<!-- 用户主动标记的永久记忆：关键决策 / 不可逆教训 / 已验证的规律 -->
<!-- 格式：- [YYYY-MM-DD] 心证内容 -->

## 主人教诲
<!-- 用户对 Agent 的长期行为指令：偏好的工作方式 / 禁止的行为 / 希望记住的习惯 -->

## 智能体对主人的承诺
<!-- Agent 对用户的承诺：府邸专属 / 数据不出本机 / 永不编造 -->

## 主人对智能体的承诺
<!-- 用户对 Agent 的承诺：定期维护 / 反馈纠偏 / 不滥用工具 -->

## 迁移验证记录
<!-- 每 2 周由用户触发 @verify migration-readiness，Agent 写入打分 -->
```

### 2. SystemPromptBuilder 集成位置

修改后 8 段顺序（在 `agent/core/system_prompt_builder.py` 中）：

```
① SOUL + IDENTITY  ← 铁律最前
② USER
③ MEMORY
③.5 HEART（心证铁卷）← 新增：优先级高于自动蒸馏的 MEMORY，低于 HEARTBEAT
④ HEARTBEAT        ← 自检铁规段
⑤ persona
⑥ whisper
⑦ tool_overlay
```

理由：心证是"应该记住什么"，铁规是"应该如何思考"——先知道记住什么，再按铁规思考。

心证内容不发送到外部 IM 渠道（`_HEART_EXCLUDED_CHANNELS = {"feishu_im", "wecom"}`）。

### 3. heart_record 工具接口

```python
# agent/tools/builtin_tools/heart_record.py

class HeartRecordTool(BaseTool):
    """心证铁卷读写工具。用户在对话中说"记住：X"时自动调用。"""

    def __init__(self):
        super().__init__(name="heart_record", category="memory")

    def execute(
        self,
        action: str,          # append | list | delete
        content: str = "",    # 心证内容（append 时必填）
        category: str = "",   # 分区：主人心证 | 主人教诲 | ...
        tags: str = "",       # 逗号分隔标签
        weight: str = "normal",  # normal | high | critical
    ) -> dict:
        ...
```

**写入规则**：
- `append`：在 `soul/heart.md` 对应 `## 分区名` 下追加行
- `list`：读取 heart.md，按分区/标签筛选返回
- `delete`：移除对应行，先 `.bak` 备份

**备份**：写入前做 `.bak.1`~`.bak.5` 轮转备份（与 MEMORY.md 同策略）。

---

## 迁移验证检查表（每 2 周 1 次）

用户触发 `@verify migration-readiness` 后，Agent 逐项提问用户打分：

| 维度 | 检查项 | 打分（0-100） |
|------|--------|--------------|
| 对话体验 | 响应速度：府邸与飞书同 prompt 回复耗时差异 < 20% | |
| | 上下文深度：10 轮连续对话后能准确引用前 3 轮细节 | |
| | 工具调用成功率 ≥ 飞书（相同任务集） | |
| 记忆持久化 | 无丢失：5 条跨会话测试记忆 24h 后全部可检索 | |
| | 无错位：蒸馏后不张冠李戴 | |
| 心证管理 | heart.md 同步率：飞书端心证 5 条全在府邸 heart.md 中 | |
| | 府邸能准确复述 3 条心证铁卷 | |

**三项全部 100% 的那天 = 迁移日。** 届时从飞书 aily 沙箱导出 SOUL/USER/MEMORY/whisper → 手动填入府邸 `soul/`。

---

## 排期

```
W1 (7/01-7/07): heart.md + SoulLoader + SystemPromptBuilder + heart_record 工具 + 5 信号撤回触发器
W2 (7/07-7/14): PWA 导航统一 + L1/L2 缓存
W3 (7/14-7/21): 模型量化 + 图片补齐 + L3/L4 命中率
W4 (7/21-7/28): 全量回归 + 迁移验证首跑 + 文档同步
```

## 涉及文件总览

| 文件 | 操作 | 对应 TODO |
|------|------|----------|
| `soul/heart.md` | Create | TODO-84 |
| `agent/soul/loader.py` | Modify (SoulData + OPTIONAL) | TODO-84 |
| `agent/core/system_prompt_builder.py` | Modify (插入 heart 段) | TODO-85 |
| `agent/tools/builtin_tools/heart_record.py` | Create | TODO-86 |
| `agent/core/tool_dispatcher.py` | Modify (注册 heart_record) | TODO-86 |
| `agent/core/conversation_flow.py` | Modify (_detect_branch_failure + _auto_retract) | TODO-87 |
| `agent/core/tool_dispatcher.py` | Modify (_execute_tool_round 重试分级) | TODO-87 |
| `agent/core/l1_cache.py` | Create | TODO-88 |
| `agent/core/l2_cache.py` | Create | TODO-89 |
| `agent/config/settings.py` | Modify (l1/l2 参数) | TODO-88/89 |
| `agent/core/agent.py` | Modify (L1/L2 插入点) | TODO-88/89 |
| `agent/api/metrics.py` | Modify (L3/L4 指标) | TODO-91 |
| `agent/memory/long_term.py` | Modify (L3 埋点) | TODO-91 |
| 根目录 MD × 4 | Modify (日期同步) | TODO-92 |
| 新增测试文件 × 5 | Create (heart/branch/l1/l2/metrics) | TODO-84~91 |

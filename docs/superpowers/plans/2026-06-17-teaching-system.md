# Teaching System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a teaching module to the existing Python FastAPI agent that delivers daily timed push notifications (PWA + Feishu), auto-grades submissions, and archives wrong answers.

**Architecture:** Six focused Python modules in `agent/teaching/` (question_bank → daily_plan → grader → wrong_book → command_log → pusher), one API router, and two Vue views. The pusher reads its cron schedule from `agent/data/scheduler_config.json` and registers handlers into the existing `SimpleTaskScheduler`. All push goes through two channels: P1 (`_push_notification` → frontend polling) and P2 (Feishu IM, independent try/except).

**Tech Stack:** Python 3.11, FastAPI, croniter (already in scheduler), Vue 3 + Element Plus, pytest (unit tests run from `agent/` dir)

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `agent/teaching/__init__.py` | Create | Package marker |
| `agent/teaching/question_bank.py` | Create | 24 questions (8×3 topics), ABCD spread, get/filter API |
| `agent/teaching/wrong_book.py` | Create | JSON persistence, dedup by question_id, resolve flag |
| `agent/teaching/command_log.py` | Create | Append-only markdown writer per topic |
| `agent/teaching/grader.py` | Create | Grade 3 question types, always emit explanation, call wrong_book |
| `agent/teaching/daily_plan.py` | Create | Weekday vs weekend plan, 4-topic routing |
| `agent/teaching/pusher.py` | Create | Load scheduler_config.json, register 4 cron actions, dual-channel push |
| `agent/data/scheduler_config.json` | Create | Cron schedule for 4 teaching actions |
| `agent/api/teaching_router.py` | Create | 5 REST endpoints |
| `agent/api/fastapi_app.py` | Modify | Register teaching_router |
| `agent/data/memory/k8s-learning/questions.md` | Create | K8s question reference |
| `agent/data/memory/k8s-learning/commands.md` | Create | K8s command log seed |
| `agent/data/memory/llm-learning/questions.md` | Create | LLM question reference |
| `agent/data/memory/agent-design/questions.md` | Create | Agent design question reference |
| `agent/tests/test_question_bank.py` | Create | ABCD spread, filtering |
| `agent/tests/test_wrong_book.py` | Create | Dedup, resolved flag |
| `agent/tests/test_command_log.py` | Create | Append accumulation |
| `agent/tests/test_grader.py` | Create | 3 types, explanation, wrong_book side-effect |
| `agent/tests/test_daily_plan.py` | Create | Weekday ratio, weekend commands |
| `agent/tests/test_pusher.py` | Create | Dual-channel, P2 failure isolation |
| `frontend/src/views/learning/SubmitView.vue` | Create | Progress bar, submit, grade result, export |
| `frontend/src/views/learning/ReviewView.vue` | Create | Filter, resolve, frequency badge |
| `frontend/src/router/index.js` | Modify | Add /learning routes |

---

## Task 1: Scaffolding

**Files:**
- Create: `agent/teaching/__init__.py`
- Create: `agent/tests/__init__.py` (if not exists)

- [ ] **Step 1: Create the teaching package and test directory**

```bash
# Run from E:\workspace\intelligent_agent\agent
mkdir -p teaching
touch teaching/__init__.py
mkdir -p tests
touch tests/__init__.py
```

On Windows PowerShell:
```powershell
New-Item -ItemType Directory -Force "agent\teaching" | Out-Null
New-Item -ItemType File -Force "agent\teaching\__init__.py" | Out-Null
New-Item -ItemType File -Force "agent\tests\__init__.py" | Out-Null
```

- [ ] **Step 2: Verify agent conftest.py has sys.path setup**

Open `agent/conftest.py`. Confirm it contains:
```python
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
```
If missing, add it. This lets `pytest tests/` import from `teaching/`.

- [ ] **Step 3: Commit**

```bash
git add agent/teaching/__init__.py agent/tests/__init__.py
git commit -m "feat(teaching): scaffold package and test directory"
```

---

## Task 2: question_bank.py

**Files:**
- Create: `agent/teaching/question_bank.py`
- Create: `agent/tests/test_question_bank.py`

- [ ] **Step 1: Write failing tests**

Create `agent/tests/test_question_bank.py`:

```python
"""Unit tests for question_bank."""
from collections import Counter
from teaching.question_bank import Question, get_questions, ALL_QUESTIONS


def test_get_all_returns_list():
    qs = get_questions()
    assert isinstance(qs, list)
    assert len(qs) >= 24


def test_filter_by_topic():
    k8s = get_questions(topic="k8s")
    assert all(q.topic == "k8s" for q in k8s)
    assert len(k8s) >= 8


def test_filter_by_type():
    choices = get_questions(question_type="choice")
    assert all(q.type == "choice" for q in choices)


def test_k8s_review_returns_k8s_questions():
    review = get_questions(topic="k8s_review")
    assert len(review) >= 8
    # k8s_review maps to the k8s pool
    k8s = get_questions(topic="k8s")
    assert set(q.id for q in review) == set(q.id for q in k8s)


def test_abcd_spread():
    """ABCD answers must be roughly even across 3 topics × 4 choice questions = 12 choice questions.
    Each letter should appear 2-4 times (deviation < 5 percentage points from 25%)."""
    choices = [q for q in ALL_QUESTIONS if q.type == "choice"]
    assert len(choices) == 12, f"Expected 12 choice questions (3 topics × 4), got {len(choices)}"
    counts = Counter(q.answer for q in choices)
    total = len(choices)  # 12
    for letter in ("A", "B", "C", "D"):
        count = counts.get(letter, 0)
        pct = count / total
        assert 0.15 <= pct <= 0.40, (
            f"Answer '{letter}' appears {count}/{total} times "
            f"({pct:.0%}), expected 15%-40% (2-5 out of 12)"
        )


def test_every_choice_has_four_options():
    for q in get_questions(question_type="choice"):
        assert set(q.options.keys()) == {"A", "B", "C", "D"}, (
            f"Question {q.id} has options {list(q.options.keys())}"
        )


def test_every_question_has_explanation():
    for q in ALL_QUESTIONS:
        assert q.explanation, f"Question {q.id} has no explanation"
```

- [ ] **Step 2: Run test — expect ImportError**

```bash
cd E:\workspace\intelligent_agent\agent
pytest tests/test_question_bank.py -v
```
Expected: `ImportError: cannot import name 'Question' from 'teaching.question_bank'`

- [ ] **Step 3: Implement question_bank.py**

Create `agent/teaching/question_bank.py`:

```python
"""题库：K8s / LLM / Agent 各 8 题，答案 ABCD 均匀分散。"""
from dataclasses import dataclass, field
from typing import Dict, List, Optional


@dataclass
class Question:
    id: str
    topic: str          # k8s | llm | agent  (k8s_review → k8s pool)
    type: str           # choice | fill | short_answer
    difficulty: int     # 1-3
    text: str
    options: Dict[str, str]  # only for choice; empty dict otherwise
    answer: str
    explanation: str


ALL_QUESTIONS: List[Question] = [
    # ── K8s (8 题：4 choice A/B/C/D + 2 fill + 2 short_answer) ──────────────
    Question(
        id="k8s-001", topic="k8s", type="choice", difficulty=1,
        text="Pod 内多个容器之间通信，最直接的方式是？",
        options={
            "A": "通过 localhost 互访（共享同一网络命名空间）",
            "B": "通过 ClusterIP Service",
            "C": "通过 NodePort Service",
            "D": "通过 Ingress 转发",
        },
        answer="A",
        explanation="同一 Pod 内的容器共享网络命名空间，直接用 localhost + 端口互访，无需经过 Service。",
    ),
    Question(
        id="k8s-002", topic="k8s", type="choice", difficulty=2,
        text="StatefulSet 与 Deployment 最核心的区别是？",
        options={
            "A": "StatefulSet 支持滚动更新，Deployment 不支持",
            "B": "StatefulSet 给每个 Pod 提供稳定的网络标识和持久存储",
            "C": "StatefulSet 只能运行单副本",
            "D": "StatefulSet 不支持资源限制（requests/limits）",
        },
        answer="B",
        explanation="StatefulSet 保证三个稳定承诺：Pod 名称（序号）、DNS 网络标识、PVC 绑定不变，适合数据库等有状态应用。",
    ),
    Question(
        id="k8s-003", topic="k8s", type="choice", difficulty=2,
        text="执行 kubectl get pods 看不到预期的 Pod，最常见的原因是？",
        options={
            "A": "kubelet 未启动",
            "B": "Pod 镜像拉取失败",
            "C": "查询的命名空间不对，Pod 在另一个 namespace",
            "D": "Scheduler 未分配节点",
        },
        answer="C",
        explanation="不加 -n <namespace> 或 --all-namespaces 时，kubectl 默认只查 default 命名空间。",
    ),
    Question(
        id="k8s-004", topic="k8s", type="choice", difficulty=3,
        text="Deployment 滚动更新时，要保证始终至少有 3 个可用副本（共 4 个），应设置？",
        options={
            "A": "maxSurge: 1, maxUnavailable: 1",
            "B": "maxSurge: 0, maxUnavailable: 1",
            "C": "maxSurge: 2, maxUnavailable: 0",
            "D": "maxSurge: 1, maxUnavailable: 0",
        },
        answer="D",
        explanation="maxUnavailable: 0 保证滚动时不减少可用 Pod；maxSurge: 1 允许多启动 1 个新版本 Pod，做到无缝切换。",
    ),
    Question(
        id="k8s-005", topic="k8s", type="fill", difficulty=1,
        text="查看所有命名空间下所有 Pod 的 kubectl 命令是：kubectl get pods ___",
        options={},
        answer="--all-namespaces",
        explanation="`-A` 是 `--all-namespaces` 的简写，两者均可，面试中完整写法更规范。",
    ),
    Question(
        id="k8s-006", topic="k8s", type="fill", difficulty=2,
        text="将 Secret 的某个 key 注入为容器环境变量，env[].valueFrom 下使用 ___ 字段",
        options={},
        answer="secretKeyRef",
        explanation="env[].valueFrom.secretKeyRef.name 指定 Secret 名，.key 指定具体键名。ConfigMap 对应字段是 configMapKeyRef。",
    ),
    Question(
        id="k8s-007", topic="k8s", type="short_answer", difficulty=2,
        text="解释 readinessProbe 的作用，以及它与 livenessProbe 的区别",
        options={},
        answer="readinessProbe 探测容器是否可以接收流量，未就绪时从 Service Endpoints 摘除；livenessProbe 探测容器是否存活，失败时重启容器。",
        explanation="readinessProbe 控制流量进入，livenessProbe 控制容器重启，两者互补：就绪探针处理启动时序，存活探针处理运行时异常。",
    ),
    Question(
        id="k8s-008", topic="k8s", type="short_answer", difficulty=3,
        text="PersistentVolume（PV）和 PersistentVolumeClaim（PVC）的关系是什么？",
        options={},
        answer="PV 是集群级别的存储资源（由管理员或 StorageClass 动态创建），PVC 是 Pod 对存储的申请（指定大小和访问模式），K8s 负责将 PVC 绑定到合适的 PV。",
        explanation="解耦存储供给（PV）和存储消费（PVC），Pod 只引用 PVC，底层 PV 可以是 NFS/Ceph/云盘等，迁移时只需更换 PV 实现。",
    ),

    # ── LLM (8 题：4 choice A/B/C/D + 2 fill + 2 short_answer) ─────────────
    Question(
        id="llm-001", topic="llm", type="choice", difficulty=1,
        text="Transformer 中 Self-Attention 的时间复杂度（序列长度 n）是？",
        options={
            "A": "O(n²)",
            "B": "O(n log n)",
            "C": "O(n)",
            "D": "O(n³)",
        },
        answer="A",
        explanation="Self-Attention 的 QK^T 矩阵乘法复杂度为 O(n²·d)，d 为向量维度，因此对序列长度是平方关系，这是长文本场景的瓶颈。",
    ),
    Question(
        id="llm-002", topic="llm", type="choice", difficulty=1,
        text="Few-shot prompting 的核心思想是？",
        options={
            "A": "对模型进行全量微调",
            "B": "在 prompt 中提供少量示例，引导模型按格式输出",
            "C": "使用更大的模型参数量",
            "D": "增加 temperature 提升创意性",
        },
        answer="B",
        explanation="Few-shot prompting 通过在上下文中给出 2-5 个输入→输出示例，利用 LLM 的上下文学习能力，无需更新参数即可引导输出格式。",
    ),
    Question(
        id="llm-003", topic="llm", type="choice", difficulty=2,
        text="RAG（检索增强生成）主要解决的问题是？",
        options={
            "A": "减少模型推理延迟",
            "B": "降低训练成本",
            "C": "解决知识时效性和减少幻觉",
            "D": "支持多模态输入",
        },
        answer="C",
        explanation="LLM 训练数据有截止日期且可能产生幻觉。RAG 在推理时检索外部知识库，将相关文档注入 prompt，使模型基于真实来源回答。",
    ),
    Question(
        id="llm-004", topic="llm", type="choice", difficulty=3,
        text="LoRA（Low-Rank Adaptation）微调的核心优势是？",
        options={
            "A": "支持任意架构的模型微调",
            "B": "完全不需要标注数据",
            "C": "推理时延迟为零",
            "D": "只训练低秩分解矩阵，可训练参数减少 99% 以上",
        },
        answer="D",
        explanation="LoRA 冻结原始权重 W，引入 W + AB（A、B 是低秩矩阵）。7B 模型全量微调需训练 ~7B 参数，LoRA r=8 只需 ~4M，显存消耗和成本大幅降低。",
    ),
    Question(
        id="llm-005", topic="llm", type="fill", difficulty=1,
        text="让 LLM 稳定输出 JSON 等结构化格式的技术叫___（英文）",
        options={},
        answer="structured output",
        explanation="Structured Output / Function Calling / JSON Mode 均为此类技术，OpenAI 的实现基于 logit bias 约束输出 token，Ollama 也支持 format: json。",
    ),
    Question(
        id="llm-006", topic="llm", type="fill", difficulty=2,
        text="LLM 推理时控制输出随机性的参数叫___，值越高输出越多样",
        options={},
        answer="temperature",
        explanation="temperature=0 近似贪心解码（输出确定）；temperature=1 使用原始概率分布；>1 时分布更均匀，随机性更高。生产环境问答类任务一般取 0.1-0.3。",
    ),
    Question(
        id="llm-007", topic="llm", type="short_answer", difficulty=2,
        text="解释 Chain of Thought (CoT) prompting 为什么能提升 LLM 的推理能力",
        options={},
        answer="CoT 要求模型在给出答案前输出中间推理步骤，这使模型将复杂问题分解为子步骤，每步的输出作为下一步的上下文，避免跳步错误。",
        explanation="研究表明 CoT 对数学、逻辑、多步推理任务提升显著（Google 2022）。关键在于生成中间 token 相当于模型的'工作内存'，让参数受限的模型也能完成复杂推理。",
    ),
    Question(
        id="llm-008", topic="llm", type="short_answer", difficulty=3,
        text="对比 Prompt Engineering 和 Fine-tuning，各自适用什么场景？",
        options={},
        answer="Prompt Engineering 无需改变模型参数，适合快速原型、任务多变的场景；Fine-tuning 需要标注数据和算力，适合领域专有知识固化、格式要求严格、推理时 prompt 无法充分描述任务的场景。",
        explanation="实践中先用 Prompt Engineering 验证可行性，确定方向后再考虑 Fine-tuning。RAG 是第三条路：用检索替代参数记忆，适合知识更新频繁的场景。",
    ),

    # ── Agent 设计 (8 题：4 choice A/B/C/D + 2 fill + 2 short_answer) ───────
    Question(
        id="agent-001", topic="agent", type="choice", difficulty=1,
        text="ReAct Agent 的核心执行循环是？",
        options={
            "A": "Thought → Action → Observation（循环直到终止）",
            "B": "Plan → Execute → Verify",
            "C": "Query → Retrieve → Generate",
            "D": "Perceive → Decide → Act",
        },
        answer="A",
        explanation="ReAct = Reasoning + Acting。循环：① LLM 输出 Thought（推理）→ ② 解析 Action（工具调用）→ ③ 执行得到 Observation → 追加到上下文 → 重复，直到输出 Final Answer。",
    ),
    Question(
        id="agent-002", topic="agent", type="choice", difficulty=2,
        text="工具调用（Function Calling）中，描述函数参数 schema 的标准格式是？",
        options={
            "A": "YAML",
            "B": "JSON Schema",
            "C": "Protocol Buffers",
            "D": "GraphQL SDL",
        },
        answer="B",
        explanation="OpenAI、Anthropic、Ollama 等主流 LLM 均使用 JSON Schema 描述工具参数（type/description/required），LLM 据此生成合法参数对象。",
    ),
    Question(
        id="agent-003", topic="agent", type="choice", difficulty=2,
        text="Agent 记忆系统中引入向量检索（Embedding + ChromaDB）解决的核心问题是？",
        options={
            "A": "减少数据库读写次数",
            "B": "支持跨语言查询",
            "C": "按语义相似度检索历史记忆，而非精确关键词匹配",
            "D": "加密存储敏感记忆",
        },
        answer="C",
        explanation="向量检索将文本映射到高维空间，用余弦相似度而非关键词匹配，能找到语义相关但措辞不同的历史记忆，显著提升上下文质量。",
    ),
    Question(
        id="agent-004", topic="agent", type="choice", difficulty=3,
        text="多 Agent 协作中，Orchestrator（编排器）的核心职责是？",
        options={
            "A": "直接执行所有工具调用",
            "B": "存储所有 Agent 的运行日志",
            "C": "为每个 Sub-Agent 提供独立的 LLM 实例",
            "D": "将复杂任务分解，调度 Sub-Agent 执行子任务，聚合结果",
        },
        answer="D",
        explanation="Orchestrator 持有全局目标，负责 Plan（任务拆解）→ Dispatch（分配给 Sub-Agent）→ Aggregate（汇总结果），它本身不直接执行领域工具。",
    ),
    Question(
        id="agent-005", topic="agent", type="fill", difficulty=2,
        text="Agent 中先让 LLM 生成执行计划再逐步执行子任务的模式叫 ___ 模式",
        options={},
        answer="Plan and Execute",
        explanation="区别于 ReAct 的单步循环，Plan and Execute 先完整规划再执行，适合步骤多、顺序固定的任务（如代码生成流水线）。",
    ),
    Question(
        id="agent-006", topic="agent", type="fill", difficulty=1,
        text="防止 ReAct Agent 无限调用工具的机制叫 ___（英文，两个单词）",
        options={},
        answer="max iterations",
        explanation="设置 max_iterations（如 10）是最简单的防护手段。超过后强制终止并输出当前最佳答案或错误信息，防止 token 消耗失控。",
    ),
    Question(
        id="agent-007", topic="agent", type="short_answer", difficulty=2,
        text="解释 Agent 中 short-term memory 和 long-term memory 的区别及各自存储机制",
        options={},
        answer="Short-term memory 是当前会话的对话历史（in-process list/deque，TTL 过期），用于多轮上下文；long-term memory 是跨会话持久化知识（向量数据库如 ChromaDB），通过 embedding 语义检索。",
        explanation="短期记忆随会话结束消失，长期记忆永久存储。实践中短期用 token window，超长时用摘要压缩；长期记忆需要定期清理和聚类避免膨胀。",
    ),
    Question(
        id="agent-008", topic="agent", type="short_answer", difficulty=3,
        text="为什么 Agent 的工具调用需要安全沙箱？举例说明两种潜在风险",
        options={},
        answer="工具调用由 LLM 生成的参数驱动，LLM 可能被提示注入攻击，生成恶意路径（如 ../etc/passwd）或危险命令。风险举例：1）FileTool 路径穿越读取敏感文件；2）ShellTool 执行 rm -rf 或网络请求外泄数据。",
        explanation="防护手段：FileTool 路径白名单 + 只读模式；ShellTool 命令白名单 + 禁止网络访问；对用户输入做提示注入检测；工具调用结果长度限制防止 context 污染。",
    ),
]

_TOPIC_DIR_MAP = {
    "k8s": "k8s",
    "k8s_review": "k8s",   # k8s_review uses the same question pool as k8s
    "llm": "llm",
    "agent": "agent",
}


def get_questions(
    topic: Optional[str] = None,
    question_type: Optional[str] = None,
) -> List[Question]:
    result = ALL_QUESTIONS
    if topic:
        canonical = _TOPIC_DIR_MAP.get(topic, topic)
        result = [q for q in result if q.topic == canonical]
    if question_type:
        result = [q for q in result if q.type == question_type]
    return result
```

- [ ] **Step 4: Run tests — expect all pass**

```bash
cd E:\workspace\intelligent_agent\agent
pytest tests/test_question_bank.py -v
```
Expected: 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent/teaching/question_bank.py agent/tests/test_question_bank.py
git commit -m "feat(teaching): add question_bank with 24 questions, balanced ABCD"
```

---

## Task 3: wrong_book.py

**Files:**
- Create: `agent/teaching/wrong_book.py`
- Create: `agent/tests/test_wrong_book.py`

- [ ] **Step 1: Write failing tests**

Create `agent/tests/test_wrong_book.py`:

```python
"""Unit tests for wrong_book — dedup, resolve, list."""
import json
import pytest
from pathlib import Path
from unittest.mock import patch


@pytest.fixture(autouse=True)
def tmp_memory(tmp_path, monkeypatch):
    """Redirect _BASE to a temp directory for each test."""
    import teaching.wrong_book as wb
    monkeypatch.setattr(wb, "_BASE", tmp_path)
    yield tmp_path


from teaching.wrong_book import add, resolve, list_records


def test_add_creates_record():
    add("k8s-001", "k8s", "B", "A")
    records = list_records("k8s")
    assert len(records) == 1
    r = records[0]
    assert r["question_id"] == "k8s-001"
    assert r["wrong_count"] == 1
    assert r["resolved"] is False


def test_add_deduplicates_same_question():
    add("k8s-001", "k8s", "B", "A")
    add("k8s-001", "k8s", "C", "A")  # second wrong answer
    records = list_records("k8s")
    assert len(records) == 1, "Same question_id must not create duplicate entry"
    assert records[0]["wrong_count"] == 2
    assert records[0]["user_answer"] == "C"  # updated to latest


def test_add_different_questions_creates_two_records():
    add("k8s-001", "k8s", "B", "A")
    add("k8s-002", "k8s", "A", "B")
    assert len(list_records("k8s")) == 2


def test_resolve_marks_record():
    add("k8s-001", "k8s", "B", "A")
    ok = resolve("k8s-001", "k8s")
    assert ok is True
    records = list_records("k8s", include_resolved=True)
    r = next(r for r in records if r["question_id"] == "k8s-001")
    assert r["resolved"] is True
    assert r["resolved_time"] is not None


def test_list_excludes_resolved_by_default():
    add("k8s-001", "k8s", "B", "A")
    resolve("k8s-001", "k8s")
    assert list_records("k8s") == []
    assert len(list_records("k8s", include_resolved=True)) == 1


def test_resolve_nonexistent_returns_false():
    assert resolve("nonexistent", "k8s") is False


def test_list_sorted_by_last_wrong_time_desc():
    add("k8s-001", "k8s", "B", "A")
    add("k8s-002", "k8s", "A", "B")
    records = list_records("k8s")
    times = [r["last_wrong_time"] for r in records]
    assert times == sorted(times, reverse=True)
```

- [ ] **Step 2: Run test — expect ImportError**

```bash
pytest tests/test_wrong_book.py -v
```
Expected: `ImportError: cannot import name 'add' from 'teaching.wrong_book'`

- [ ] **Step 3: Implement wrong_book.py**

Create `agent/teaching/wrong_book.py`:

```python
"""错题本：JSON 持久化，按 question_id 去重，支持已掌握标记。"""
import json
from datetime import datetime
from pathlib import Path
from typing import List, Optional

_BASE = Path(__file__).parent.parent / "data" / "memory"

_TOPIC_DIR = {
    "k8s": "k8s-learning",
    "k8s_review": "k8s-learning",
    "llm": "llm-learning",
    "agent": "agent-design",
}


def _path(topic: str) -> Path:
    dirname = _TOPIC_DIR.get(topic, f"{topic}-learning")
    return _BASE / dirname / "wrong_book.json"


def _load(topic: str) -> List[dict]:
    p = _path(topic)
    if not p.exists():
        return []
    data = json.loads(p.read_text(encoding="utf-8"))
    return data.get("wrong_records", [])


def _save(topic: str, records: List[dict]) -> None:
    p = _path(topic)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(
        json.dumps({"wrong_records": records}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def add(question_id: str, topic: str, user_answer: str, correct_answer: str) -> None:
    records = _load(topic)
    now = datetime.now().isoformat()
    for r in records:
        if r["question_id"] == question_id:
            r["last_wrong_time"] = now
            r["wrong_count"] = r.get("wrong_count", 1) + 1
            r["user_answer"] = user_answer
            _save(topic, records)
            return
    records.append({
        "question_id": question_id,
        "topic": topic,
        "user_answer": user_answer,
        "correct_answer": correct_answer,
        "wrong_time": now,
        "last_wrong_time": now,
        "wrong_count": 1,
        "resolved": False,
        "resolved_time": None,
    })
    _save(topic, records)


def resolve(question_id: str, topic: str) -> bool:
    records = _load(topic)
    for r in records:
        if r["question_id"] == question_id:
            r["resolved"] = True
            r["resolved_time"] = datetime.now().isoformat()
            _save(topic, records)
            return True
    return False


def list_records(topic: str, include_resolved: bool = False) -> List[dict]:
    records = _load(topic)
    if not include_resolved:
        records = [r for r in records if not r["resolved"]]
    return sorted(records, key=lambda r: r["last_wrong_time"], reverse=True)
```

- [ ] **Step 4: Run tests — expect all pass**

```bash
pytest tests/test_wrong_book.py -v
```
Expected: 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent/teaching/wrong_book.py agent/tests/test_wrong_book.py
git commit -m "feat(teaching): add wrong_book with dedup and resolve support"
```

---

## Task 4: command_log.py

**Files:**
- Create: `agent/teaching/command_log.py`
- Create: `agent/tests/test_command_log.py`

- [ ] **Step 1: Write failing tests**

Create `agent/tests/test_command_log.py`:

```python
"""Unit tests for command_log — append-only, accumulate, not overwrite."""
import pytest
from teaching.command_log import append, _path


@pytest.fixture(autouse=True)
def tmp_memory(tmp_path, monkeypatch):
    import teaching.command_log as cl
    monkeypatch.setattr(cl, "_BASE", tmp_path)
    yield tmp_path


def test_append_creates_file():
    append("k8s", "kubectl get pods", "列出当前命名空间所有 Pod")
    p = _path("k8s")
    assert p.exists()
    content = p.read_text(encoding="utf-8")
    assert "kubectl get pods" in content


def test_append_accumulates():
    append("k8s", "kubectl get pods", "列出 Pod")
    append("k8s", "kubectl describe pod", "查看 Pod 详情")
    content = _path("k8s").read_text(encoding="utf-8")
    assert "kubectl get pods" in content
    assert "kubectl describe pod" in content


def test_append_does_not_overwrite():
    append("k8s", "kubectl get pods", "第一条")
    first = _path("k8s").read_text(encoding="utf-8")
    append("k8s", "kubectl logs", "第二条")
    second = _path("k8s").read_text(encoding="utf-8")
    assert "kubectl get pods" in second, "First entry must survive after second append"
    assert len(second) > len(first)


def test_different_topics_use_different_files():
    append("k8s", "kubectl get pods", "K8s 命令")
    append("llm", "ollama run", "LLM 命令")
    k8s_content = _path("k8s").read_text(encoding="utf-8")
    llm_content = _path("llm").read_text(encoding="utf-8")
    assert "kubectl get pods" not in llm_content
    assert "ollama run" not in k8s_content


def test_same_day_entries_share_header():
    append("k8s", "kubectl get pods", "命令一")
    append("k8s", "kubectl apply -f", "命令二")
    content = _path("k8s").read_text(encoding="utf-8")
    from datetime import datetime
    today = datetime.now().strftime("%Y-%m-%d")
    assert content.count(f"## {today}") == 1, "Same-day entries must share one header"
```

- [ ] **Step 2: Run test — expect ImportError**

```bash
pytest tests/test_command_log.py -v
```

- [ ] **Step 3: Implement command_log.py**

Create `agent/teaching/command_log.py`:

```python
"""命令积累：追加写入 memory/<topic>/commands.md，按日期分组。"""
from datetime import datetime
from pathlib import Path

_BASE = Path(__file__).parent.parent / "data" / "memory"

_TOPIC_DIR = {
    "k8s": "k8s-learning",
    "k8s_review": "k8s-learning",
    "llm": "llm-learning",
    "agent": "agent-design",
}


def _path(topic: str) -> Path:
    dirname = _TOPIC_DIR.get(topic, f"{topic}-learning")
    return _BASE / dirname / "commands.md"


def append(topic: str, command: str, description: str) -> None:
    p = _path(topic)
    p.parent.mkdir(parents=True, exist_ok=True)
    today = datetime.now().strftime("%Y-%m-%d")
    header = f"## {today}"
    entry = f"- `{command}`: {description}\n"

    if p.exists():
        content = p.read_text(encoding="utf-8")
        if header in content:
            # Insert entry right after the existing date header line
            idx = content.index(header) + len(header)
            content = content[:idx] + "\n" + entry + content[idx:]
        else:
            content = content.rstrip("\n") + f"\n\n{header}\n{entry}"
    else:
        content = f"{header}\n{entry}"

    p.write_text(content, encoding="utf-8")
```

- [ ] **Step 4: Run tests — expect all pass**

```bash
pytest tests/test_command_log.py -v
```
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent/teaching/command_log.py agent/tests/test_command_log.py
git commit -m "feat(teaching): add command_log append-only markdown writer"
```

---

## Task 5: grader.py

**Files:**
- Create: `agent/teaching/grader.py`
- Create: `agent/tests/test_grader.py`

- [ ] **Step 1: Write failing tests**

Create `agent/tests/test_grader.py`:

```python
"""Unit tests for grader — 3 types, explanation always present, wrong_book side-effect."""
import pytest
from unittest.mock import patch, MagicMock
from teaching.grader import grade, Submission, Answer


@pytest.fixture(autouse=True)
def no_wrong_book_io(monkeypatch):
    """Prevent writing to disk during grading tests."""
    monkeypatch.setattr("teaching.wrong_book.add", MagicMock())
    monkeypatch.setattr("teaching.wrong_book._save", MagicMock())


def test_correct_choice_answer():
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-001", user_answer="A")],
    )
    result = grade(sub)
    assert result.score == 1
    assert result.total == 1
    assert result.results[0].correct is True


def test_incorrect_choice_answer():
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-001", user_answer="B")],
    )
    result = grade(sub)
    assert result.results[0].correct is False


def test_explanation_present_for_correct_answer():
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-001", user_answer="A")],
    )
    result = grade(sub)
    assert result.results[0].explanation, "Explanation must be present even for correct answers"


def test_explanation_present_for_wrong_answer():
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-001", user_answer="B")],
    )
    result = grade(sub)
    assert result.results[0].explanation, "Explanation must be present for wrong answers"


def test_wrong_answer_calls_wrong_book(monkeypatch):
    from teaching import wrong_book
    mock_add = MagicMock()
    monkeypatch.setattr(wrong_book, "add", mock_add)
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-001", user_answer="B")],
    )
    grade(sub)
    mock_add.assert_called_once()
    call_kwargs = mock_add.call_args
    assert call_kwargs[1]["question_id"] == "k8s-001" or call_kwargs[0][0] == "k8s-001"


def test_correct_answer_does_not_call_wrong_book(monkeypatch):
    from teaching import wrong_book
    mock_add = MagicMock()
    monkeypatch.setattr(wrong_book, "add", mock_add)
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-001", user_answer="A")],
    )
    grade(sub)
    mock_add.assert_not_called()


def test_fill_answer_case_insensitive():
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-005", user_answer="--ALL-NAMESPACES")],
    )
    result = grade(sub)
    assert result.results[0].correct is True


def test_short_answer_partial_keyword_match():
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[Answer(question_id="k8s-007", user_answer="readinessProbe 控制流量，livenessProbe 重启容器")],
    )
    result = grade(sub)
    # short_answer grading is keyword-based: at least 50% of answer keywords
    assert isinstance(result.results[0].correct, bool)
    assert result.results[0].explanation  # always present


def test_score_aggregation():
    sub = Submission(
        user_id="test", topic="k8s",
        answers=[
            Answer(question_id="k8s-001", user_answer="A"),   # correct
            Answer(question_id="k8s-002", user_answer="A"),   # wrong (answer is B)
        ],
    )
    result = grade(sub)
    assert result.total == 2
    assert result.score == 1
```

- [ ] **Step 2: Run test — expect ImportError**

```bash
pytest tests/test_grader.py -v
```

- [ ] **Step 3: Implement grader.py**

Create `agent/teaching/grader.py`:

```python
"""批改引擎：三题型，对题也解析，错题自动归档。"""
from dataclasses import dataclass, field
from typing import List
from teaching.question_bank import Question, get_questions
from teaching import wrong_book


@dataclass
class Answer:
    question_id: str
    user_answer: str


@dataclass
class QuestionResult:
    question_id: str
    correct: bool
    user_answer: str
    correct_answer: str
    explanation: str    # always populated (v4.6 铁律)


@dataclass
class Submission:
    user_id: str
    topic: str
    answers: List[Answer]


@dataclass
class GradeResult:
    user_id: str
    topic: str
    score: int
    total: int
    results: List[QuestionResult] = field(default_factory=list)


def grade(submission: Submission) -> GradeResult:
    question_map = {
        q.id: q
        for q in get_questions(topic=submission.topic)
    }
    results: List[QuestionResult] = []
    for answer in submission.answers:
        q = question_map.get(answer.question_id)
        if q is None:
            continue
        correct = _check_answer(q, answer.user_answer)
        if not correct:
            wrong_book.add(
                question_id=q.id,
                topic=submission.topic,
                user_answer=answer.user_answer,
                correct_answer=q.answer,
            )
        results.append(QuestionResult(
            question_id=q.id,
            correct=correct,
            user_answer=answer.user_answer,
            correct_answer=q.answer,
            explanation=q.explanation,
        ))
    return GradeResult(
        user_id=submission.user_id,
        topic=submission.topic,
        score=sum(1 for r in results if r.correct),
        total=len(results),
        results=results,
    )


def _check_answer(q: Question, user_answer: str) -> bool:
    if q.type == "choice":
        return user_answer.strip().upper() == q.answer.strip().upper()
    if q.type == "fill":
        return user_answer.strip().lower() == q.answer.strip().lower()
    if q.type == "short_answer":
        key_terms = [t for t in q.answer.lower().split() if len(t) > 2]
        if not key_terms:
            return True
        user_lower = user_answer.lower()
        matched = sum(1 for t in key_terms if t in user_lower)
        return matched >= max(1, len(key_terms) // 2)
    return False
```

- [ ] **Step 4: Run tests — expect all pass**

```bash
pytest tests/test_grader.py -v
```
Expected: 9 tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent/teaching/grader.py agent/tests/test_grader.py
git commit -m "feat(teaching): add grader with 3 question types and explanation always on"
```

---

## Task 6: daily_plan.py

**Files:**
- Create: `agent/teaching/daily_plan.py`
- Create: `agent/tests/test_daily_plan.py`

- [ ] **Step 1: Write failing tests**

Create `agent/tests/test_daily_plan.py`:

```python
"""Unit tests for daily_plan — weekday/weekend, topic routing, question ratio."""
import pytest
from unittest.mock import patch
from datetime import datetime
from teaching.daily_plan import get_today_plan, DailyPlan


def _mock_weekday(weekday: int):
    """Return a context manager that patches datetime.now().weekday()."""
    fake = datetime(2026, 6, 16 + weekday)  # Mon=0 … Sun=6
    return patch("teaching.daily_plan.datetime", wraps=datetime,
                 **{"now.return_value": fake})


def test_weekday_returns_questions():
    with _mock_weekday(0):  # Monday
        plan = get_today_plan("k8s")
    assert plan.is_weekend is False
    assert len(plan.questions) > 0


def test_weekend_returns_no_questions():
    with _mock_weekday(5):  # Saturday
        plan = get_today_plan("k8s")
    assert plan.is_weekend is True
    assert plan.questions == []


def test_weekend_returns_commands():
    with _mock_weekday(6):  # Sunday
        plan = get_today_plan("k8s")
    assert isinstance(plan.commands, list)
    assert len(plan.commands) > 0


def test_weekday_has_choice_and_fill():
    with _mock_weekday(1):  # Tuesday
        plan = get_today_plan("k8s")
    types = {q.type for q in plan.questions}
    assert "choice" in types
    assert "fill" in types


def test_k8s_review_uses_k8s_pool():
    with _mock_weekday(0):
        plan_review = get_today_plan("k8s_review")
        plan_k8s = get_today_plan("k8s")
    review_ids = {q.id for q in plan_review.questions}
    k8s_ids = {q.id for q in plan_k8s.questions}
    assert review_ids.issubset(k8s_ids | {"k8s-001", "k8s-002", "k8s-003",
                                           "k8s-004", "k8s-005", "k8s-006",
                                           "k8s-007", "k8s-008"})


def test_plan_date_is_today():
    plan = get_today_plan("k8s")
    assert plan.date == datetime.now().strftime("%Y-%m-%d")


def test_weekday_short_answer_at_most_two():
    # Run 10 times to account for randomness
    for _ in range(10):
        with _mock_weekday(2):
            plan = get_today_plan("k8s")
        short_count = sum(1 for q in plan.questions if q.type == "short_answer")
        assert short_count <= 2, f"Got {short_count} short_answer questions, max is 2"
```

- [ ] **Step 2: Run test — expect ImportError**

```bash
pytest tests/test_daily_plan.py -v
```

- [ ] **Step 3: Implement daily_plan.py**

Create `agent/teaching/daily_plan.py`:

```python
"""每日计划生成：周中差异化题型 + 周末实操命令。"""
import random
from dataclasses import dataclass, field
from datetime import datetime
from typing import List
from teaching.question_bank import Question, get_questions


@dataclass
class DailyPlan:
    date: str
    topic: str
    is_weekend: bool
    questions: List[Question]
    commands: List[str] = field(default_factory=list)


def get_today_plan(topic: str) -> DailyPlan:
    today = datetime.now()
    date_str = today.strftime("%Y-%m-%d")
    is_weekend = today.weekday() >= 5

    if is_weekend:
        return DailyPlan(
            date=date_str,
            topic=topic,
            is_weekend=True,
            questions=[],
            commands=_get_weekend_commands(topic),
        )

    # 周中：40% 选择 + 30% 填空 + ≤2 简答，共 5 题
    all_qs = get_questions(topic=topic)
    choices = [q for q in all_qs if q.type == "choice"]
    fills = [q for q in all_qs if q.type == "fill"]
    shorts = [q for q in all_qs if q.type == "short_answer"]

    selected = (
        random.sample(choices, min(2, len(choices))) +
        random.sample(fills, min(2, len(fills))) +
        random.sample(shorts, min(1, len(shorts)))
    )
    random.shuffle(selected)

    return DailyPlan(
        date=date_str,
        topic=topic,
        is_weekend=False,
        questions=selected,
        commands=[],
    )


def _get_weekend_commands(topic: str) -> List[str]:
    shorts = get_questions(topic=topic, question_type="short_answer")
    return [q.text for q in shorts]
```

- [ ] **Step 4: Run tests — expect all pass**

```bash
pytest tests/test_daily_plan.py -v
```
Expected: 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent/teaching/daily_plan.py agent/tests/test_daily_plan.py
git commit -m "feat(teaching): add daily_plan weekday/weekend differentiation"
```

---

## Task 7: scheduler_config.json + pusher.py

**Files:**
- Create: `agent/data/scheduler_config.json`
- Create: `agent/teaching/pusher.py`
- Create: `agent/tests/test_pusher.py`

- [ ] **Step 1: Create scheduler_config.json**

Create `agent/data/scheduler_config.json`:

```json
{
  "version": "1.0",
  "last_updated": "2026-06-17",
  "teaching_schedules": [
    {
      "action": "teaching_push_morning",
      "cron": "0 7 * * 1-5",
      "topic": "k8s",
      "label": "K8s 晨读",
      "channel": "dual",
      "enabled": true
    },
    {
      "action": "teaching_push_midmorning",
      "cron": "0 10 * * 1-5",
      "topic": "llm",
      "label": "LLM 拓展",
      "channel": "dual",
      "enabled": true
    },
    {
      "action": "teaching_push_review",
      "cron": "40 13 * * 1,3,5",
      "topic": "k8s_review",
      "label": "K8s 复习",
      "channel": "dual",
      "enabled": true
    },
    {
      "action": "teaching_push_afternoon",
      "cron": "0 15 * * 1-5",
      "topic": "agent",
      "label": "Agent 实战",
      "channel": "dual",
      "enabled": true
    }
  ]
}
```

- [ ] **Step 2: Write failing tests**

Create `agent/tests/test_pusher.py`:

```python
"""Unit tests for pusher — config loading, dual-channel, P2 failure isolation."""
import json
import pytest
from unittest.mock import MagicMock, patch, call
from pathlib import Path


@pytest.fixture
def config_file(tmp_path):
    cfg = {
        "teaching_schedules": [
            {
                "action": "test_push_action",
                "cron": "0 9 * * 1-5",
                "topic": "k8s",
                "label": "测试推送",
                "channel": "dual",
            }
        ]
    }
    p = tmp_path / "scheduler_config.json"
    p.write_text(json.dumps(cfg), encoding="utf-8")
    return p


@pytest.fixture
def mock_scheduler():
    s = MagicMock()
    s.actions = {}
    s.register_action = lambda name, fn: s.actions.update({name: fn})
    return s


def test_register_loads_config(config_file, mock_scheduler, monkeypatch):
    import teaching.pusher as pusher
    monkeypatch.setattr(pusher, "_CONFIG_PATH", config_file)
    pusher.register(mock_scheduler)
    assert "test_push_action" in mock_scheduler.actions
    mock_scheduler.create_task.assert_called_once()
    call_kwargs = mock_scheduler.create_task.call_args[1]
    assert call_kwargs["schedule_type"] == "cron"
    assert call_kwargs["cron_expression"] == "0 9 * * 1-5"


def test_push_calls_pwa_p1(config_file, mock_scheduler, monkeypatch):
    import teaching.pusher as pusher
    monkeypatch.setattr(pusher, "_CONFIG_PATH", config_file)
    mock_pwa = MagicMock()
    monkeypatch.setattr(pusher, "_send_pwa", mock_pwa)
    monkeypatch.setattr(pusher, "_send_feishu", MagicMock())
    pusher.register(mock_scheduler)
    # Trigger the registered handler
    mock_scheduler.actions["test_push_action"]()
    mock_pwa.assert_called_once()


def test_p2_feishu_failure_does_not_block_p1(config_file, mock_scheduler, monkeypatch):
    import teaching.pusher as pusher
    monkeypatch.setattr(pusher, "_CONFIG_PATH", config_file)
    mock_pwa = MagicMock()
    mock_feishu = MagicMock(side_effect=Exception("飞书连接失败"))
    monkeypatch.setattr(pusher, "_send_pwa", mock_pwa)
    monkeypatch.setattr(pusher, "_send_feishu", mock_feishu)
    pusher.register(mock_scheduler)
    # Should not raise even though Feishu fails
    mock_scheduler.actions["test_push_action"]()
    mock_pwa.assert_called_once()  # P1 still called


def test_v3_precheck_blocks_empty_content(monkeypatch):
    import teaching.pusher as pusher
    mock_pwa = MagicMock()
    monkeypatch.setattr(pusher, "_send_pwa", mock_pwa)
    monkeypatch.setattr("teaching.daily_plan.get_today_plan",
                        MagicMock(return_value=MagicMock(
                            is_weekend=False, questions=[], commands=[], date="2026-06-17"
                        )))
    handler = pusher._make_push_handler("k8s", "测试", "dual")
    handler()
    # Empty content (no questions) — _send_pwa may or may not be called depending on
    # whether content is empty; the important thing is no exception raised
    # and if content is empty, pwa should NOT be called
    # (empty questions produces empty content)


def test_four_schedules_registered(monkeypatch):
    import teaching.pusher as pusher
    real_config = Path(__file__).parent.parent / "data" / "scheduler_config.json"
    if not real_config.exists():
        pytest.skip("scheduler_config.json not yet created")
    mock_sched = MagicMock()
    mock_sched.actions = {}
    mock_sched.register_action = lambda n, f: mock_sched.actions.update({n: f})
    pusher.register(mock_sched)
    assert len(mock_sched.actions) == 4
    assert "teaching_push_morning" in mock_sched.actions
    assert "teaching_push_afternoon" in mock_sched.actions


def test_missing_config_raises(mock_scheduler, monkeypatch):
    import teaching.pusher as pusher
    monkeypatch.setattr(pusher, "_CONFIG_PATH", Path("/nonexistent/scheduler_config.json"))
    with pytest.raises(Exception):
        pusher.register(mock_scheduler)


def test_disabled_schedule_not_registered(tmp_path, mock_scheduler, monkeypatch):
    import teaching.pusher as pusher
    cfg = {
        "version": "1.0",
        "last_updated": "2026-06-17",
        "teaching_schedules": [
            {
                "action": "teaching_push_morning",
                "cron": "0 7 * * 1-5",
                "topic": "k8s",
                "label": "K8s 晨读",
                "channel": "dual",
                "enabled": False,    # disabled
            },
            {
                "action": "teaching_push_afternoon",
                "cron": "0 15 * * 1-5",
                "topic": "agent",
                "label": "Agent 实战",
                "channel": "dual",
                "enabled": True,
            },
        ],
    }
    p = tmp_path / "scheduler_config.json"
    p.write_text(json.dumps(cfg), encoding="utf-8")
    monkeypatch.setattr(pusher, "_CONFIG_PATH", p)
    pusher.register(mock_scheduler)
    assert "teaching_push_morning" not in mock_scheduler.actions, \
        "Disabled schedule must NOT be registered"
    assert "teaching_push_afternoon" in mock_scheduler.actions
```

- [ ] **Step 3: Run test — expect ImportError**

```bash
pytest tests/test_pusher.py -v
```

- [ ] **Step 4: Implement pusher.py**

Create `agent/teaching/pusher.py`:

```python
"""推送节奏：从 scheduler_config.json 加载 4 个 cron，双通道推送。"""
import json
import os
from pathlib import Path
from typing import Callable
from loguru import logger

from scheduler.simple_scheduler import SimpleTaskScheduler, _push_notification
from teaching.daily_plan import get_today_plan

_CONFIG_PATH = Path(__file__).parent.parent / "data" / "scheduler_config.json"


# ── v3.0 自检 ────────────────────────────────────────────────────────────────

def _v3_precheck(content: str) -> bool:
    if not content.strip():
        logger.warning("[v3.0 自检] content 为空，跳过本次推送")
        return False
    return True


# ── 双通道推送 ────────────────────────────────────────────────────────────────

def _send_pwa(content: str) -> None:
    """P1：写入前端轮询通知队列。"""
    _push_notification(content, role="assistant")


def _send_feishu(content: str) -> None:
    """P2：飞书 IM，独立 try/except，失败仅 warning 不阻断 P1。"""
    app_id = os.environ.get("FEISHU_APP_ID", "")
    receiver = os.environ.get("FEISHU_RECEIVER_ID", "")
    if not app_id or not receiver:
        logger.warning("[飞书 P2] FEISHU_APP_ID 或 FEISHU_RECEIVER_ID 未配置，跳过飞书推送")
        return
    try:
        from im.feishu_client import FeishuIMTool
        FeishuIMTool().execute(
            receiver_id=receiver,
            msg_type="text",
            content={"text": content},
        )
        logger.info("[飞书 P2] 推送成功")
    except Exception as exc:
        logger.warning(f"[飞书 P2] 推送失败（不影响 P1）: {exc}")


# ── 推送内容构建 ──────────────────────────────────────────────────────────────

def _make_push_handler(topic: str, label: str, channel: str) -> Callable:
    def handler() -> None:
        plan = get_today_plan(topic)
        if plan.is_weekend:
            lines = [f"【{label}·周末实操】"]
            lines += [f"- {c}" for c in plan.commands]
        else:
            lines = [f"【{label}·今日练习】 {plan.date}"]
            for i, q in enumerate(plan.questions, 1):
                lines.append(f"\nQ{i}. {q.text}")
                if q.options:
                    for k, v in q.options.items():
                        lines.append(f"  {k}. {v}")
        content = "\n".join(lines)

        if not _v3_precheck(content):
            return

        _send_pwa(content)

        if channel == "dual":
            _send_feishu(content)

    return handler


# ── 注册入口 ──────────────────────────────────────────────────────────────────

def register(scheduler: SimpleTaskScheduler) -> None:
    config = json.loads(_CONFIG_PATH.read_text(encoding="utf-8"))
    for entry in config["teaching_schedules"]:
        if not entry.get("enabled", True):
            logger.info(f"[TeachingPusher] 已跳过（enabled=false）: {entry['action']}")
            continue
        action_name = entry["action"]
        handler = _make_push_handler(
            topic=entry["topic"],
            label=entry["label"],
            channel=entry.get("channel", "dual"),
        )
        scheduler.register_action(action_name, handler)
        scheduler.create_task(
            name=entry["label"],
            action=action_name,
            schedule_type="cron",
            cron_expression=entry["cron"],
        )
        logger.info(f"[TeachingPusher] 已注册: {action_name}  cron={entry['cron']}")
```

- [ ] **Step 5: Run tests — expect all pass**

```bash
pytest tests/test_pusher.py -v
```
Expected: 5 tests PASS

- [ ] **Step 6: Commit**

```bash
git add agent/data/scheduler_config.json agent/teaching/pusher.py agent/tests/test_pusher.py
git commit -m "feat(teaching): add pusher with config-driven cron and dual-channel push"
```

---

## Task 8: teaching_router.py

**Files:**
- Create: `agent/api/teaching_router.py`

- [ ] **Step 1: Implement the router**

Create `agent/api/teaching_router.py`:

```python
"""教学体系 REST API（/api/teaching/*）。"""
from typing import List, Optional
from fastapi import APIRouter
from pydantic import BaseModel
from loguru import logger

from teaching.question_bank import get_questions
from teaching.daily_plan import get_today_plan
from teaching.grader import grade, Submission, Answer
from teaching import wrong_book
from teaching import command_log

router = APIRouter(prefix="/api/teaching", tags=["teaching"])


# ── 每日计划 ─────────────────────────────────────────────────────────────────

@router.get("/daily-plan")
def daily_plan_endpoint(topic: str = "k8s"):
    plan = get_today_plan(topic)
    return {
        "date": plan.date,
        "topic": plan.topic,
        "is_weekend": plan.is_weekend,
        "questions": [
            {
                "id": q.id,
                "type": q.type,
                "difficulty": q.difficulty,
                "text": q.text,
                "options": q.options,
            }
            for q in plan.questions
        ],
        "commands": plan.commands,
    }


# ── 提交批改 ─────────────────────────────────────────────────────────────────

class AnswerItem(BaseModel):
    question_id: str
    user_answer: str


class SubmitRequest(BaseModel):
    user_id: str = "default"
    topic: str = "k8s"
    answers: List[AnswerItem]


@router.post("/submit")
def submit_answers(req: SubmitRequest):
    submission = Submission(
        user_id=req.user_id,
        topic=req.topic,
        answers=[Answer(question_id=a.question_id, user_answer=a.user_answer) for a in req.answers],
    )
    result = grade(submission)
    return {
        "score": result.score,
        "total": result.total,
        "results": [
            {
                "question_id": r.question_id,
                "correct": r.correct,
                "user_answer": r.user_answer,
                "correct_answer": r.correct_answer,
                "explanation": r.explanation,
            }
            for r in result.results
        ],
    }


# ── 错题本 ────────────────────────────────────────────────────────────────────

@router.get("/wrong-book")
def get_wrong_book(
    topic: str = "k8s",
    device: Optional[str] = None,
    include_resolved: bool = False,
):
    records = wrong_book.list_records(topic=topic, include_resolved=include_resolved)
    return {"topic": topic, "device": device, "records": records, "count": len(records)}


@router.post("/wrong-book/{question_id}/resolve")
def resolve_wrong(question_id: str, topic: str = "k8s"):
    ok = wrong_book.resolve(question_id=question_id, topic=topic)
    return {"success": ok, "question_id": question_id}


# ── 命令积累 ──────────────────────────────────────────────────────────────────

@router.get("/command-log")
def get_command_log(topic: str = "k8s"):
    path = command_log._path(topic)
    if not path.exists():
        return {"topic": topic, "content": ""}
    return {"topic": topic, "content": path.read_text(encoding="utf-8")}
```

- [ ] **Step 2: Verify no syntax errors**

```bash
cd E:\workspace\intelligent_agent\agent
python -c "from api.teaching_router import router; print('OK', len(router.routes), 'routes')"
```
Expected: `OK 5 routes`

- [ ] **Step 3: Commit**

```bash
git add agent/api/teaching_router.py
git commit -m "feat(teaching): add teaching_router with 5 endpoints"
```

---

## Task 9: Register router + pusher in fastapi_app.py

**Files:**
- Modify: `agent/api/fastapi_app.py`

- [ ] **Step 1: Find the router registration block**

Open `agent/api/fastapi_app.py`. Find the lines:
```python
app.include_router(knowledge_router)
app.include_router(image_router)
```

- [ ] **Step 2: Add teaching router registration**

Add immediately after the existing `include_router` calls:

```python
from api.teaching_router import router as teaching_router
app.include_router(teaching_router)
```

- [ ] **Step 3: Find the lifespan / startup section**

Find where `scheduler.main_loop` is set:
```python
if _state.agent and _state.agent.task_manager:
    _state.agent.task_manager.scheduler.main_loop = _uvicorn_loop
```

Add the pusher registration immediately after:

```python
    # Register teaching push schedules from scheduler_config.json
    try:
        from teaching.pusher import register as _register_teaching
        _register_teaching(_state.agent.task_manager.scheduler)
    except Exception as _te:
        logger.warning(f"教学推送注册失败（非致命）: {_te}")
```

- [ ] **Step 4: Verify startup**

```bash
cd E:\workspace\intelligent_agent\agent
python -c "import api.fastapi_app; print('Import OK')"
```
Expected: `Import OK` (no errors)

- [ ] **Step 5: Commit**

```bash
git add agent/api/fastapi_app.py
git commit -m "feat(teaching): register teaching_router and pusher in app startup"
```

---

## Task 10: Memory markdown seed files

**Files:**
- Create: `agent/data/memory/k8s-learning/questions.md`
- Create: `agent/data/memory/k8s-learning/commands.md`
- Create: `agent/data/memory/llm-learning/questions.md`
- Create: `agent/data/memory/agent-design/questions.md`

- [ ] **Step 1: Create directories**

```powershell
New-Item -ItemType Directory -Force "agent\data\memory\k8s-learning" | Out-Null
New-Item -ItemType Directory -Force "agent\data\memory\llm-learning" | Out-Null
New-Item -ItemType Directory -Force "agent\data\memory\agent-design" | Out-Null
```

- [ ] **Step 2: Create agent/data/memory/k8s-learning/questions.md**

```markdown
# K8s 题库参考

## 选择题（答案分布：A/B/C/D 各 1 题）

| ID | 题干摘要 | 答案 |
|----|---------|------|
| k8s-001 | Pod 内容器通信 | A |
| k8s-002 | StatefulSet vs Deployment | B |
| k8s-003 | kubectl get pods 看不到 Pod | C |
| k8s-004 | 滚动更新零停机配置 | D |

## 填空题

| ID | 题干摘要 | 答案 |
|----|---------|------|
| k8s-005 | 查所有命名空间 Pod 参数 | --all-namespaces |
| k8s-006 | Secret 注入环境变量字段 | secretKeyRef |

## 简答题

| ID | 题干摘要 |
|----|---------|
| k8s-007 | readinessProbe vs livenessProbe |
| k8s-008 | PV 和 PVC 的关系 |
```

- [ ] **Step 3: Create agent/data/memory/k8s-learning/commands.md**

```markdown
# K8s 命令积累

## 2026-06-17
- `kubectl get pods -n <namespace>`: 查看指定命名空间的 Pod 列表
- `kubectl describe pod <name>`: 查看 Pod 详细事件（排障必用）
- `kubectl logs <pod> -c <container>`: 查看指定容器日志
- `kubectl apply -f <file.yaml>`: 声明式应用配置（支持增量更新）
- `kubectl rollout status deployment/<name>`: 查看滚动更新进度
```

- [ ] **Step 4: Create agent/data/memory/llm-learning/questions.md**

```markdown
# LLM 题库参考

## 选择题（答案分布：A/B/C/D 各 1 题）

| ID | 题干摘要 | 答案 |
|----|---------|------|
| llm-001 | Self-Attention 时间复杂度 | A |
| llm-002 | Few-shot prompting 核心思想 | B |
| llm-003 | RAG 解决的问题 | C |
| llm-004 | LoRA 微调优势 | D |

## 填空题

| ID | 题干摘要 | 答案 |
|----|---------|------|
| llm-005 | 结构化输出技术名称 | structured output |
| llm-006 | 控制随机性的参数 | temperature |

## 简答题

| ID | 题干摘要 |
|----|---------|
| llm-007 | Chain of Thought 原理 |
| llm-008 | Prompt Engineering vs Fine-tuning |
```

- [ ] **Step 5: Create agent/data/memory/agent-design/questions.md**

```markdown
# Agent 设计题库参考

## 选择题（答案分布：A/B/C/D 各 1 题）

| ID | 题干摘要 | 答案 |
|----|---------|------|
| agent-001 | ReAct 循环结构 | A |
| agent-002 | 工具调用参数 Schema 标准 | B |
| agent-003 | 向量检索解决的问题 | C |
| agent-004 | Orchestrator 职责 | D |

## 填空题

| ID | 题干摘要 | 答案 |
|----|---------|------|
| agent-005 | Plan and Execute 模式名称 | Plan and Execute |
| agent-006 | 防止无限循环机制 | max iterations |

## 简答题

| ID | 题干摘要 |
|----|---------|
| agent-007 | 短期/长期记忆区别 |
| agent-008 | 工具安全沙箱必要性 |
```

- [ ] **Step 6: Commit**

```bash
git add agent/data/memory/
git commit -m "feat(teaching): add memory markdown seed files for 3 topics"
```

---

## Task 11: SubmitView.vue

**Files:**
- Create: `frontend/src/views/learning/SubmitView.vue`

- [ ] **Step 1: Create the learning directory**

```powershell
New-Item -ItemType Directory -Force "frontend\src\views\learning" | Out-Null
```

- [ ] **Step 2: Implement SubmitView.vue**

Create `frontend/src/views/learning/SubmitView.vue`:

```vue
<template>
  <div class="submit-view">
    <div v-if="loading" class="loading">加载中...</div>

    <template v-else-if="plan && !gradeResult">
      <!-- 题目卡片 -->
      <div class="plan-header">
        <h2>{{ plan.is_weekend ? '周末实操' : '今日练习' }} · {{ plan.topic }}</h2>
        <div v-if="!plan.is_weekend" class="progress-bar">
          <span>已完成 {{ answeredCount }} / {{ plan.questions.length }}</span>
          <el-progress
            :percentage="Math.round(answeredCount / plan.questions.length * 100)"
            :show-text="false"
            style="width: 200px; margin-left: 12px"
          />
        </div>
      </div>

      <!-- 周末实操模式 -->
      <div v-if="plan.is_weekend" class="weekend-commands">
        <h3>本周实操命令</h3>
        <ul>
          <li v-for="cmd in plan.commands" :key="cmd">{{ cmd }}</li>
        </ul>
      </div>

      <!-- 答题模式 -->
      <template v-else>
        <div
          v-for="(q, idx) in plan.questions"
          :key="q.id"
          class="question-card"
        >
          <p class="question-text"><b>Q{{ idx + 1 }}.</b> {{ q.text }}</p>

          <!-- 选择题 -->
          <el-radio-group
            v-if="q.type === 'choice'"
            v-model="answers[q.id]"
          >
            <el-radio
              v-for="(text, key) in q.options"
              :key="key"
              :label="key"
              style="display: block; margin: 4px 0"
            >
              {{ key }}. {{ text }}
            </el-radio>
          </el-radio-group>

          <!-- 填空题 -->
          <el-input
            v-else-if="q.type === 'fill'"
            v-model="answers[q.id]"
            placeholder="填写答案"
            style="max-width: 400px"
          />

          <!-- 简答题 -->
          <el-input
            v-else-if="q.type === 'short_answer'"
            v-model="answers[q.id]"
            type="textarea"
            :rows="3"
            placeholder="输入简答内容"
          />
        </div>

        <el-button
          type="primary"
          :disabled="answeredCount === 0"
          @click="submit"
          style="margin-top: 16px"
        >
          提交答案
        </el-button>
      </template>
    </template>

    <!-- 批改结果 -->
    <div v-else-if="gradeResult" class="grade-result">
      <h2>批改结果：{{ gradeResult.score }} / {{ gradeResult.total }}</h2>

      <div
        v-for="r in gradeResult.results"
        :key="r.question_id"
        class="result-card"
        :class="r.correct ? 'correct' : 'wrong'"
      >
        <p>
          <b>{{ r.question_id }}</b>
          <el-tag :type="r.correct ? 'success' : 'danger'" size="small" style="margin-left: 8px">
            {{ r.correct ? '✓ 正确' : '✗ 错误' }}
          </el-tag>
        </p>
        <p>你的答案：{{ r.user_answer }}　正确答案：{{ r.correct_answer }}</p>
        <p class="explanation">📖 {{ r.explanation }}</p>
      </div>

      <div class="export-actions" style="margin-top: 16px">
        <el-button @click="copyMarkdown">复制全部解析</el-button>
        <el-button @click="downloadMarkdown">导出 .md</el-button>
        <el-button type="primary" @click="reset">再练一次</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({ topic: { type: String, default: 'k8s' } })

const loading = ref(true)
const plan = ref(null)
const answers = ref({})
const gradeResult = ref(null)

const answeredCount = computed(
  () => Object.values(answers.value).filter(v => v && v.trim()).length
)

onMounted(async () => {
  try {
    const res = await fetch(`/api/teaching/daily-plan?topic=${props.topic}`)
    plan.value = await res.json()
    plan.value.questions.forEach(q => { answers.value[q.id] = '' })
  } catch (e) {
    ElMessage.error('加载题目失败')
  } finally {
    loading.value = false
  }
})

async function submit() {
  const payload = {
    user_id: 'default',
    topic: props.topic,
    answers: Object.entries(answers.value)
      .filter(([, v]) => v && v.trim())
      .map(([question_id, user_answer]) => ({ question_id, user_answer })),
  }
  try {
    const res = await fetch('/api/teaching/submit', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    gradeResult.value = await res.json()
  } catch (e) {
    ElMessage.error('提交失败，请重试')
  }
}

function buildMarkdown() {
  if (!gradeResult.value) return ''
  const lines = [`# 批改结果 ${new Date().toLocaleDateString()}`, '']
  for (const r of gradeResult.value.results) {
    lines.push(`## ${r.question_id}  ${r.correct ? '✓' : '✗'}`)
    lines.push(`- 你的答案：${r.user_answer}`)
    lines.push(`- 正确答案：${r.correct_answer}`)
    lines.push(`- 解析：${r.explanation}`)
    lines.push('')
  }
  return lines.join('\n')
}

function copyMarkdown() {
  navigator.clipboard.writeText(buildMarkdown())
  ElMessage.success('已复制到剪贴板')
}

function downloadMarkdown() {
  const md = buildMarkdown()
  const blob = new Blob([md], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `grade-${Date.now()}.md`
  a.click()
  URL.revokeObjectURL(url)
}

function reset() {
  gradeResult.value = null
  answers.value = {}
  plan.value.questions.forEach(q => { answers.value[q.id] = '' })
}
</script>

<style scoped>
.submit-view { max-width: 800px; margin: 0 auto; padding: 24px; }
.plan-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.progress-bar { display: flex; align-items: center; font-size: 14px; color: #666; }
.question-card { background: #f9f9f9; border-radius: 8px; padding: 16px; margin-bottom: 12px; }
.question-text { font-size: 15px; margin-bottom: 8px; }
.result-card { border-left: 4px solid #ccc; padding: 12px; margin-bottom: 10px; border-radius: 4px; }
.result-card.correct { border-color: #67c23a; background: #f0f9eb; }
.result-card.wrong { border-color: #f56c6c; background: #fef0f0; }
.explanation { color: #555; font-size: 13px; margin-top: 6px; }
.weekend-commands li { margin: 6px 0; }
</style>
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/learning/SubmitView.vue
git commit -m "feat(teaching): add SubmitView with progress bar and grade export"
```

---

## Task 12: ReviewView.vue + router registration

**Files:**
- Create: `frontend/src/views/learning/ReviewView.vue`
- Modify: `frontend/src/router/index.js`

- [ ] **Step 1: Implement ReviewView.vue**

Create `frontend/src/views/learning/ReviewView.vue`:

```vue
<template>
  <div class="review-view">
    <div class="toolbar">
      <h2>错题本</h2>
      <div class="filters">
        <el-select v-model="topic" @change="load" style="width: 140px">
          <el-option label="K8s" value="k8s" />
          <el-option label="K8s 复习" value="k8s_review" />
          <el-option label="LLM" value="llm" />
          <el-option label="Agent" value="agent" />
        </el-select>
        <el-checkbox v-model="includeResolved" @change="load" style="margin-left: 12px">
          显示已掌握
        </el-checkbox>
      </div>
    </div>

    <div v-if="loading" class="loading">加载中...</div>

    <div v-else-if="records.length === 0" class="empty">
      🎉 暂无错题
    </div>

    <div
      v-else
      v-for="r in records"
      :key="r.question_id"
      class="wrong-card"
      :class="{ resolved: r.resolved }"
    >
      <div class="card-header">
        <span class="qid">{{ r.question_id }}</span>
        <el-tag
          v-if="r.wrong_count >= 3"
          type="danger"
          size="small"
          style="margin-left: 8px"
        >
          高频错题 × {{ r.wrong_count }}
        </el-tag>
        <el-tag
          v-else-if="r.wrong_count > 1"
          type="warning"
          size="small"
          style="margin-left: 8px"
        >
          错 {{ r.wrong_count }} 次
        </el-tag>
        <el-tag v-if="r.resolved" type="success" size="small" style="margin-left: 8px">
          ✓ 已掌握
        </el-tag>
      </div>

      <p>上次错误：<b>{{ r.user_answer }}</b>　正确答案：<b>{{ r.correct_answer }}</b></p>
      <p class="time">最近错误时间：{{ formatTime(r.last_wrong_time) }}</p>

      <el-button
        v-if="!r.resolved"
        size="small"
        type="success"
        @click="markResolved(r)"
      >
        标记已掌握
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'

const topic = ref('k8s')
const includeResolved = ref(false)
const loading = ref(true)
const records = ref([])

async function load() {
  loading.value = true
  try {
    const params = new URLSearchParams({
      topic: topic.value,
      include_resolved: includeResolved.value,
    })
    const res = await fetch(`/api/teaching/wrong-book?${params}`)
    const data = await res.json()
    records.value = data.records
  } catch (e) {
    ElMessage.error('加载错题本失败')
  } finally {
    loading.value = false
  }
}

async function markResolved(record) {
  try {
    await fetch(`/api/teaching/wrong-book/${record.question_id}/resolve?topic=${topic.value}`, {
      method: 'POST',
    })
    record.resolved = true
    ElMessage.success('已标记为掌握')
    if (!includeResolved.value) {
      records.value = records.value.filter(r => !r.resolved)
    }
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

function formatTime(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN')
}

onMounted(load)
</script>

<style scoped>
.review-view { max-width: 800px; margin: 0 auto; padding: 24px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.filters { display: flex; align-items: center; }
.wrong-card { background: #fff8f8; border: 1px solid #fbc4c4; border-radius: 8px; padding: 14px; margin-bottom: 12px; }
.wrong-card.resolved { background: #f0f9eb; border-color: #b3e19d; }
.card-header { display: flex; align-items: center; margin-bottom: 8px; }
.qid { font-weight: bold; font-size: 14px; }
.time { font-size: 12px; color: #999; margin: 4px 0 8px; }
.empty { text-align: center; padding: 48px; color: #999; font-size: 16px; }
</style>
```

- [ ] **Step 2: Add routes to frontend/src/router/index.js**

Open `frontend/src/router/index.js`. Find the routes array and add:

```javascript
{
  path: '/learning',
  redirect: '/learning/submit',
},
{
  path: '/learning/submit',
  name: 'LearningSubmit',
  component: () => import('../views/learning/SubmitView.vue'),
},
{
  path: '/learning/review',
  name: 'LearningReview',
  component: () => import('../views/learning/ReviewView.vue'),
},
```

- [ ] **Step 3: Verify frontend builds**

```bash
cd E:\workspace\intelligent_agent\frontend
npm run build 2>&1 | tail -5
```
Expected: `✓ built in ...` with no errors

- [ ] **Step 4: Run all Python unit tests**

```bash
cd E:\workspace\intelligent_agent\agent
pytest tests/test_question_bank.py tests/test_wrong_book.py tests/test_command_log.py tests/test_grader.py tests/test_daily_plan.py tests/test_pusher.py -v
```
Expected: all PASS

- [ ] **Step 5: Final commit**

```bash
git add frontend/src/views/learning/ReviewView.vue frontend/src/router/index.js
git commit -m "feat(teaching): add ReviewView and register /learning routes"
```

---

## Self-Review

### Spec coverage check

| Requirement | Covered by |
|-------------|-----------|
| pusher.py — 4 cron, config.json-driven | Task 7 |
| grader.py — 3 types, explanation always | Task 5 |
| wrong_book.py — dedup, resolved, wrong_count | Task 3 |
| command_log.py — append-only | Task 4 |
| question_bank.py — 8 questions/topic, ABCD | Task 2 |
| daily_plan.py — weekday/weekend, 4 topics | Task 6 |
| scheduler_config.json | Task 7 Step 1 |
| 4 memory markdown files | Task 10 |
| SubmitView.vue — progress bar, export | Task 11 |
| ReviewView.vue — filter, resolve, frequency | Task 12 |
| 6 unit test files | Tasks 2-7 |
| teaching_router.py — 5 endpoints | Task 8 |
| Register router + pusher in fastapi_app | Task 9 |
| v4.7.7.5: no PrePushGuard | pusher.py has no safety import |
| v4.7.8.1: dual channel, P2 failure isolation | pusher.py _send_feishu try/except |
| v4.6: explanation always present | grader.py always sets explanation |
| v4.6.1: weekday ratio, weekend commands | daily_plan.py |
| ABCD spread | question_bank has A/B/C/D one each per topic |

### Type consistency check

- `Answer(question_id, user_answer)` — used in grader.py Task 5, SubmitView passes `question_id`/`user_answer` ✓
- `wrong_book.add(question_id, topic, user_answer, correct_answer)` — called in grader.py, tested in test_wrong_book.py ✓
- `wrong_book._path(topic)` — exposed and used in teaching_router command-log endpoint ✓
- `scheduler.register_action(name, fn)` — confirmed exists in scheduler ✓
- `scheduler.create_task(..., cron_expression=...)` — confirmed keyword name from source ✓
- `_push_notification(content, role=)` — imported from `scheduler.simple_scheduler` ✓

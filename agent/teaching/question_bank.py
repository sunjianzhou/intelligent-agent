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

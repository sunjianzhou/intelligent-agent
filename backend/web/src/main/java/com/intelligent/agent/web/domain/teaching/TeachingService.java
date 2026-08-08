package com.intelligent.agent.web.domain.teaching;

import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教学体系领域服务（Plan 2 / Task 4）：
 * 每日计划 / 提交批改 / 错题本 / 命令积累，行为与 Python teaching 模块对齐。
 */
@Slf4j
public class TeachingService {

    private final JsonFileStore store;

    public TeachingService(Path dataDir) {
        this.store = new JsonFileStore(dataDir);
    }

    public Map<String, Object> dailyPlan(String topic) {
        String canonical = canonicalTopic(topic);
        LocalDateTime now = LocalDateTime.now();
        boolean weekend = now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", now.toLocalDate().toString());
        result.put("topic", topic);
        result.put("is_weekend", weekend);
        if (weekend) {
            List<String> commands = questions(canonical).stream()
                    .filter(q -> q.type().equals("short_answer"))
                    .map(Question::text)
                    .toList();
            result.put("questions", List.of());
            result.put("commands", commands);
            return result;
        }

        List<Question> choices = filter(canonical, "choice");
        List<Question> fills = filter(canonical, "fill");
        List<Question> shorts = filter(canonical, "short_answer");
        List<Question> selected = new ArrayList<>();
        selected.addAll(sample(choices, Math.min(2, choices.size())));
        selected.addAll(sample(fills, Math.min(2, fills.size())));
        selected.addAll(sample(shorts, Math.min(1, shorts.size())));
        Collections.shuffle(selected);

        List<Map<String, Object>> questions = new ArrayList<>();
        for (Question q : selected) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", q.id());
            entry.put("type", q.type());
            entry.put("difficulty", q.difficulty());
            entry.put("text", q.text());
            entry.put("options", q.options());
            questions.add(entry);
        }
        result.put("questions", questions);
        result.put("commands", List.of());
        return result;
    }

    public Map<String, Object> submit(String userId, String topic, List<Map<String, Object>> answers) {
        String canonical = canonicalTopic(topic);
        Map<String, Question> bank = new LinkedHashMap<>();
        for (Question q : questions(canonical)) {
            bank.put(q.id(), q);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int score = 0;
        for (Map<String, Object> answer : answers) {
            String questionId = str(answer.get("question_id"));
            String userAnswer = str(answer.get("user_answer"));
            Question q = bank.get(questionId);
            if (q == null) {
                continue;
            }
            boolean correct = checkAnswer(q, userAnswer);
            if (!correct) {
                addWrong(q.id(), topic, userAnswer, q.answer());
            }
            if (correct) {
                score++;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("question_id", q.id());
            result.put("correct", correct);
            result.put("user_answer", userAnswer);
            result.put("correct_answer", q.answer());
            result.put("explanation", q.explanation());
            results.add(result);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("score", score);
        response.put("total", results.size());
        response.put("results", results);
        return response;
    }

    public Map<String, Object> wrongBook(String topic, boolean includeResolved) {
        List<Map<String, Object>> records = wrongRecords(topic);
        if (!includeResolved) {
            records = records.stream()
                    .filter(r -> !Boolean.TRUE.equals(r.get("resolved"))).toList();
        }
        records = new ArrayList<>(records);
        records.sort((a, b) -> String.valueOf(b.getOrDefault("last_wrong_time", ""))
                .compareTo(String.valueOf(a.getOrDefault("last_wrong_time", ""))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topic", topic);
        result.put("device", null);
        result.put("records", records);
        result.put("count", records.size());
        return result;
    }

    public Map<String, Object> resolveWrong(String questionId, String topic) {
        List<Map<String, Object>> records = wrongRecords(topic);
        boolean ok = false;
        for (Map<String, Object> record : records) {
            if (questionId.equals(record.get("question_id"))) {
                record.put("resolved", true);
                record.put("resolved_time", Instant.now().toString());
                ok = true;
                break;
            }
        }
        if (ok) {
            saveWrongRecords(topic, records);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", ok);
        result.put("question_id", questionId);
        return result;
    }

    public Map<String, Object> commandLog(String topic) {
        Path path = store.baseDir().resolve("teaching").resolve("command_log")
                .resolve(JsonFileStore.safe(topic) + ".txt");
        String content = "";
        if (Files.exists(path)) {
            try {
                content = Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("读取命令日志失败: {}", e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topic", topic);
        result.put("content", content);
        return result;
    }

    // ── 内部辅助 ──────────────────────────────────────────────

    private void addWrong(String questionId, String topic, String userAnswer, String correctAnswer) {
        List<Map<String, Object>> records = wrongRecords(topic);
        String now = Instant.now().toString();
        for (Map<String, Object> record : records) {
            if (questionId.equals(record.get("question_id"))) {
                record.put("last_wrong_time", now);
                record.put("wrong_count", ((Number) record.getOrDefault("wrong_count", 1)).intValue() + 1);
                record.put("user_answer", userAnswer);
                saveWrongRecords(topic, records);
                return;
            }
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("question_id", questionId);
        record.put("topic", topic);
        record.put("user_answer", userAnswer);
        record.put("correct_answer", correctAnswer);
        record.put("wrong_time", now);
        record.put("last_wrong_time", now);
        record.put("wrong_count", 1);
        record.put("resolved", false);
        record.put("resolved_time", null);
        records.add(record);
        saveWrongRecords(topic, records);
    }

    private List<Map<String, Object>> wrongRecords(String topic) {
        Map<String, Object> data = store.read("teaching", "wrong_book",
                JsonFileStore.safe(canonicalTopic(topic)) + ".json");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = data == null ? new ArrayList<>()
                : (List<Map<String, Object>>) data.getOrDefault("wrong_records", new ArrayList<>());
        return new ArrayList<>(records);
    }

    private void saveWrongRecords(String topic, List<Map<String, Object>> records) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("wrong_records", records);
        store.write(new String[]{"teaching", "wrong_book",
                JsonFileStore.safe(canonicalTopic(topic)) + ".json"}, data);
    }

    private static boolean checkAnswer(Question q, String userAnswer) {
        String answer = userAnswer == null ? "" : userAnswer.trim();
        if (q.type().equals("choice")) {
            return answer.equalsIgnoreCase(q.answer().trim());
        }
        if (q.type().equals("fill")) {
            return answer.equalsIgnoreCase(q.answer().trim());
        }
        if (q.type().equals("short_answer")) {
            String[] keyTerms = q.answer().toLowerCase().split("\\s+");
            int terms = 0;
            for (String term : keyTerms) {
                if (term.length() > 2) terms++;
            }
            if (terms == 0) {
                return true;
            }
            String lower = answer.toLowerCase();
            int matched = 0;
            for (String term : keyTerms) {
                if (term.length() > 2 && lower.contains(term)) matched++;
            }
            return matched >= Math.max(1, terms / 2);
        }
        return false;
    }

    private static List<Question> sample(List<Question> source, int n) {
        List<Question> copy = new ArrayList<>(source);
        Collections.shuffle(copy);
        return n >= copy.size() ? copy : copy.subList(0, n);
    }

    private static List<Question> filter(String canonical, String type) {
        return questions(canonical).stream().filter(q -> q.type().equals(type)).toList();
    }

    private static String canonicalTopic(String topic) {
        if (topic == null) return "k8s";
        return switch (topic) {
            case "k8s_review" -> "k8s";
            case "llm" -> "llm";
            case "agent" -> "agent";
            default -> "k8s";
        };
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record Question(String id, String topic, String type, int difficulty,
                            String text, Map<String, String> options,
                            String answer, String explanation) {
    }

    private static final List<Question> ALL_QUESTIONS = List.of(
            q("k8s-001", "k8s", "choice", 1, "Pod 内多个容器之间通信，最直接的方式是？",
                    Map.of("A", "通过 localhost 互访（共享同一网络命名空间）", "B", "通过 ClusterIP Service",
                            "C", "通过 NodePort Service", "D", "通过 Ingress 转发"),
                    "A", "同一 Pod 内的容器共享网络命名空间，直接用 localhost + 端口互访。"),
            q("k8s-002", "k8s", "choice", 2, "StatefulSet 与 Deployment 最核心的区别是？",
                    Map.of("A", "StatefulSet 支持滚动更新，Deployment 不支持",
                            "B", "StatefulSet 给每个 Pod 提供稳定的网络标识和持久存储",
                            "C", "StatefulSet 只能运行单副本",
                            "D", "StatefulSet 不支持资源限制"),
                    "B", "StatefulSet 保证 Pod 名称（序号）、DNS 网络标识、PVC 绑定不变。"),
            q("k8s-003", "k8s", "choice", 2, "执行 kubectl get pods 看不到预期的 Pod，最常见的原因是？",
                    Map.of("A", "kubelet 未启动", "B", "Pod 镜像拉取失败",
                            "C", "查询的命名空间不对，Pod 在另一个 namespace",
                            "D", "Scheduler 未分配节点"),
                    "C", "不加 -n 或 --all-namespaces 时，kubectl 默认只查 default 命名空间。"),
            q("k8s-004", "k8s", "choice", 3,
                    "Deployment 滚动更新时，要保证始终至少有 3 个可用副本（共 4 个），应设置？",
                    Map.of("A", "maxSurge: 1, maxUnavailable: 1", "B", "maxSurge: 0, maxUnavailable: 1",
                            "C", "maxSurge: 2, maxUnavailable: 0", "D", "maxSurge: 1, maxUnavailable: 0"),
                    "D", "maxUnavailable: 0 保证滚动时不减少可用 Pod；maxSurge: 1 允许多启动 1 个新版本。"),
            q("k8s-005", "k8s", "fill", 1,
                    "查看所有命名空间下所有 Pod 的 kubectl 命令是：kubectl get pods ___",
                    Map.of(), "--all-namespaces", "-A 是 --all-namespaces 的简写，完整写法更规范。"),
            q("k8s-006", "k8s", "fill", 2,
                    "将 Secret 的某个 key 注入为容器环境变量，env[].valueFrom 下使用 ___ 字段",
                    Map.of(), "secretKeyRef", "env[].valueFrom.secretKeyRef.name 指定 Secret 名。"),
            q("k8s-007", "k8s", "short_answer", 2,
                    "解释 readinessProbe 的作用，以及它与 livenessProbe 的区别",
                    Map.of(), "readinessProbe 探测容器是否可以接收流量，未就绪时从 Service Endpoints 摘除；livenessProbe 探测容器是否存活，失败时重启容器。",
                    "readinessProbe 控制流量进入，livenessProbe 控制容器重启，两者互补。"),
            q("k8s-008", "k8s", "short_answer", 3,
                    "PersistentVolume（PV）和 PersistentVolumeClaim（PVC）的关系是什么？",
                    Map.of(), "PV 是集群级别的存储资源（由管理员或 StorageClass 动态创建），PVC 是 Pod 对存储的申请（指定大小和访问模式），K8s 负责将 PVC 绑定到合适的 PV。",
                    "解耦存储供给（PV）和存储消费（PVC），Pod 只引用 PVC。"),
            q("llm-001", "llm", "choice", 1, "Transformer 中 Self-Attention 的时间复杂度（序列长度 n）是？",
                    Map.of("A", "O(n²)", "B", "O(n log n)", "C", "O(n)", "D", "O(n³)"),
                    "A", "Self-Attention 的 QK^T 矩阵乘法复杂度为 O(n²·d)。"),
            q("llm-002", "llm", "choice", 1, "Few-shot prompting 的核心思想是？",
                    Map.of("A", "对模型进行全量微调", "B", "在 prompt 中提供少量示例，引导模型按格式输出",
                            "C", "使用更大的模型参数量", "D", "增加 temperature 提升创意性"),
                    "B", "Few-shot 利用 LLM 的上下文学习能力，无需更新参数。"),
            q("llm-003", "llm", "choice", 2, "RAG（检索增强生成）主要解决的问题是？",
                    Map.of("A", "减少模型推理延迟", "B", "降低训练成本",
                            "C", "解决知识时效性和减少幻觉", "D", "支持多模态输入"),
                    "C", "RAG 在推理时检索外部知识库，使模型基于真实来源回答。"),
            q("llm-004", "llm", "choice", 3, "LoRA（Low-Rank Adaptation）微调的核心优势是？",
                    Map.of("A", "支持任意架构的模型微调", "B", "完全不需要标注数据",
                            "C", "推理时延迟为零", "D", "只训练低秩分解矩阵，可训练参数减少 99% 以上"),
                    "D", "LoRA 冻结原始权重，引入低秩矩阵 AB，7B 模型只需训练约 4M 参数。"),
            q("llm-005", "llm", "fill", 1, "让 LLM 稳定输出 JSON 等结构化格式的技术叫___（英文）",
                    Map.of(), "structured output", "Structured Output / Function Calling / JSON Mode 均为此类技术。"),
            q("llm-006", "llm", "fill", 2, "LLM 推理时控制输出随机性的参数叫___，值越高输出越多样",
                    Map.of(), "temperature", "temperature=0 近似贪心解码；生产问答一般取 0.1-0.3。"),
            q("llm-007", "llm", "short_answer", 2,
                    "解释 Chain of Thought (CoT) prompting 为什么能提升 LLM 的推理能力",
                    Map.of(), "CoT 要求模型在给出答案前输出中间推理步骤，这使模型将复杂问题分解为子步骤，每步的输出作为下一步的上下文，避免跳步错误。",
                    "生成中间 token 相当于模型的'工作内存'。"),
            q("llm-008", "llm", "short_answer", 3,
                    "对比 Prompt Engineering 和 Fine-tuning，各自适用什么场景？",
                    Map.of(), "Prompt Engineering 无需改变模型参数，适合快速原型、任务多变的场景；Fine-tuning 需要标注数据和算力，适合领域专有知识固化、格式要求严格的场景。",
                    "实践中先用 Prompt Engineering 验证可行性，RAG 是第三条路。"),
            q("agent-001", "agent", "choice", 1, "ReAct Agent 的核心执行循环是？",
                    Map.of("A", "Thought → Action → Observation（循环直到终止）", "B", "Plan → Execute → Verify",
                            "C", "Query → Retrieve → Generate", "D", "Perceive → Decide → Act"),
                    "A", "ReAct = Reasoning + Acting，循环直到输出 Final Answer。"),
            q("agent-002", "agent", "choice", 2, "工具调用（Function Calling）中，描述函数参数 schema 的标准格式是？",
                    Map.of("A", "YAML", "B", "JSON Schema", "C", "Protocol Buffers", "D", "GraphQL SDL"),
                    "B", "主流 LLM 均使用 JSON Schema 描述工具参数。"),
            q("agent-003", "agent", "choice", 2,
                    "Agent 记忆系统中引入向量检索（Embedding + ChromaDB）解决的核心问题是？",
                    Map.of("A", "减少数据库读写次数", "B", "支持跨语言查询",
                            "C", "按语义相似度检索历史记忆，而非精确关键词匹配", "D", "加密存储敏感记忆"),
                    "C", "向量检索用余弦相似度找到语义相关但措辞不同的历史记忆。"),
            q("agent-004", "agent", "choice", 3, "多 Agent 协作中，Orchestrator（编排器）的核心职责是？",
                    Map.of("A", "直接执行所有工具调用", "B", "存储所有 Agent 的运行日志",
                            "C", "为每个 Sub-Agent 提供独立的 LLM 实例",
                            "D", "将复杂任务分解，调度 Sub-Agent 执行子任务，聚合结果"),
                    "D", "Orchestrator 负责 Plan → Dispatch → Aggregate。"),
            q("agent-005", "agent", "fill", 2,
                    "Agent 中先让 LLM 生成执行计划再逐步执行子任务的模式叫 ___ 模式",
                    Map.of(), "Plan and Execute", "区别于 ReAct 的单步循环，先完整规划再执行。"),
            q("agent-006", "agent", "fill", 1,
                    "防止 ReAct Agent 无限调用工具的机制叫 ___（英文，两个单词）",
                    Map.of(), "max iterations", "设置 max_iterations 防止 token 消耗失控。"),
            q("agent-007", "agent", "short_answer", 2,
                    "解释 Agent 中 short-term memory 和 long-term memory 的区别及各自存储机制",
                    Map.of(), "Short-term memory 是当前会话的对话历史（in-process list/deque，TTL 过期）；long-term memory 是跨会话持久化知识（向量数据库如 ChromaDB），通过 embedding 语义检索。",
                    "短期记忆随会话结束消失，长期记忆永久存储。"),
            q("agent-008", "agent", "short_answer", 3,
                    "为什么 Agent 的工具调用需要安全沙箱？举例说明两种潜在风险",
                    Map.of(), "工具调用由 LLM 生成的参数驱动，LLM 可能被提示注入攻击，生成恶意路径（如 ../etc/passwd）或危险命令。风险举例：1）FileTool 路径穿越读取敏感文件；2）ShellTool 执行 rm -rf 或网络请求外泄数据。",
                    "防护：路径白名单 + 只读模式、命令白名单、提示注入检测。")
    );

    private static List<Question> questions(String canonical) {
        return ALL_QUESTIONS.stream().filter(q -> q.topic().equals(canonical)).toList();
    }

    private static Question q(String id, String topic, String type, int difficulty, String text,
                              Map<String, String> options, String answer, String explanation) {
        return new Question(id, topic, type, difficulty, text, options, answer, explanation);
    }
}

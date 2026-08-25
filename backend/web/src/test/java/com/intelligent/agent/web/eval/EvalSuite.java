package com.intelligent.agent.web.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.LlmProviderRouter;
import com.intelligent.agent.web.dto.request.ChatRequest;
import com.intelligent.agent.web.service.AgentService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3 LLM 评估体系（v1 基线版）：
 * 从 {@code src/test/resources/eval/golden-cases.json} 加载 golden 用例，
 * 走真实 {@link AgentService}（Ollama 推理 + 工具执行），再用 LLM-as-judge
 * 按 rubric（0-10）打分，结果 JSONL 落盘到 {@code target/eval-results/}。
 *
 * <p>运行方式：{@code mvn -Peval test}（默认全量测试通过 excludedGroups 排除本类）。
 * v1 默认保护线 {@code eval.min-score=2}（低于任何已见用例得分，只拦"完全失败"
 * 级回归）；质量门可覆盖 {@code -Deval.min-score=7}，低于阈值的用例将使构建失败。
 * 基线参考：2026-08-17 首跑平均 6.13；2026-08-21 第二轮平均 7.13（最低 web-001=2）。
 *
 * <p>R-06：golden-cases 扩充到 36 例（工具组合 / 多轮对话 / 注入攻击 / 长会话压缩 /
 * 记忆纠错等）；支持 {@code -Deval.model} 指定云端模型跑 baseline 对比；
 * {@code -Deval.samples=N}（默认 1）多次采样取中位数降低评测抖动；
 * {@code -Deval.cases=id1,id2} 只跑指定用例（调试/增量回归用）。</p>
 */
@Slf4j
@Tag("eval")
@SpringBootTest
class EvalSuite {

    private static final Pattern JSON_FENCE = Pattern.compile(
            "```(?:json)?\\s*(.*?)```", Pattern.DOTALL);
    private static final Pattern SCORE_PATTERN = Pattern.compile(
            "\"score\"\\s*[:：]\\s*(\\d{1,2})");

    @Autowired
    private AgentService agentService;

    @Autowired
    private LlmProviderRouter llmProviderRouter;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${ai.llm.ollama.model:qwen2.5:7b}")
    private String defaultModel;

    @Test
    void runGoldenEval() throws Exception {
        List<EvalCase> cases = loadCases();
        assertThat(cases).isNotEmpty();

        Path outDir = Path.of("target", "eval-results");
        Files.createDirectories(outDir);
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outFile = outDir.resolve("eval-" + runId + ".jsonl");

        List<CaseResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            CaseResult r = runCase(c);
            results.add(r);
            Files.writeString(outFile,
                    objectMapper.writeValueAsString(r) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            log.info("[eval] {} status={} score={} ({})", c.id(), r.status(), r.score(), r.latencyMs() + "ms");
        }

        long ok = results.stream().filter(r -> "ok".equals(r.status())).count();
        long error = results.stream().filter(r -> "error".equals(r.status())).count();
        double avg = results.stream()
                .filter(r -> r.score() != null)
                .mapToInt(CaseResult::score)
                .average().orElse(0);
        log.info("[eval] 完成 {} 个用例：ok={} error={} 平均分={} 结果文件={}",
                results.size(), ok, error, String.format("%.2f", avg), outFile.toAbsolutePath());
        String evalModel = evalModel();
        log.info("[eval] 生效模型={}", evalModel.isBlank() ? defaultModel : evalModel);

        // 基线模式：只要求至少 1 个用例真实跑通（全挂说明环境/接线问题）
        assertThat(error).isLessThan(results.size());

        // 分数门槛：默认保护线 2（拦灾难性回归）；质量门用 -Deval.min-score=7 覆盖；
        // 用例可声明 minScore 覆盖全局（如安全 canary 类已知短板用例）
        double min = Double.parseDouble(System.getProperty("eval.min-score", "2"));
        Map<String, EvalCase> byId = new java.util.HashMap<>();
        for (EvalCase c : cases) {
            byId.put(c.id(), c);
        }
        for (CaseResult r : results) {
            EvalCase c = byId.get(r.id());
            double caseMin = c == null || c.minScore() == null ? min : c.minScore();
            assertThat(r.score())
                    .as("用例 %s 得分 %d 低于门槛 %.0f：%s", r.id(),
                            r.score() == null ? -1 : r.score(), caseMin, r.reasons())
                    .isNotNull()
                    .isGreaterThanOrEqualTo((int) Math.ceil(caseMin));
        }
    }

    private CaseResult runCase(EvalCase c) {
        long start = System.currentTimeMillis();
        try {
            String userId = c.userId() == null || c.userId().isBlank()
                    ? "eval-user" : c.userId();
            String model = evalModel();
            String response = "";
            List<String> turns = new ArrayList<>();
            turns.add(c.prompt());
            if (c.conversation() != null) {
                turns.addAll(c.conversation());
            }
            // 多轮对话：逐轮走真实 AgentService（同一 userId 共享短期/长期记忆）
            for (String message : turns) {
                ChatRequest req = new ChatRequest();
                req.setMessage(message);
                req.setUserId(userId);
                req.setUseTools(c.useTools() == null || c.useTools());
                req.setUseMemory(true);
                req.setChannel("web");
                req.setSceneChatType(c.sceneChatType());
                req.setSceneMentioned(c.sceneMentioned() != null && c.sceneMentioned());
                if (!model.isBlank()) {
                    req.setModel(model);
                }
                response = String.valueOf(
                        agentService.chatFull(req).getOrDefault("response", ""));
            }
            // -Deval.samples=N（默认 1）：多次采样取中位数，降低 LLM 评测抖动
            int samples = Math.max(1, evalSamples());
            List<Integer> scores = new ArrayList<>();
            List<String> reasons = new ArrayList<>();
            for (int i = 0; i < samples; i++) {
                JudgeOutput judge = judge(c, response);
                if (judge.score() != null) {
                    scores.add(judge.score());
                }
                if (judge.reasons() != null && !judge.reasons().isBlank()) {
                    reasons.add(judge.reasons());
                }
            }
            Integer score = scores.isEmpty() ? null : median(scores);
            return new CaseResult(c.id(), c.category(), c.prompt(), response,
                    c.expectedTool(), c.expectedPoints(), score,
                    String.join(" | ", reasons), "ok",
                    System.currentTimeMillis() - start,
                    model.isBlank() ? defaultModel : model);
        } catch (Exception e) {
            log.error("[eval] 用例 {} 执行失败: {}", c.id(), e.getMessage());
            return new CaseResult(c.id(), c.category(), c.prompt(), "",
                    c.expectedTool(), c.expectedPoints(), null, e.getMessage(),
                    "error", System.currentTimeMillis() - start,
                    evalModel().isBlank() ? defaultModel : evalModel());
        }
    }

    /** LLM-as-judge：按 rubric 对模型回答打分（temperature=0，要求纯 JSON 输出）。 */
    private JudgeOutput judge(EvalCase c, String response) {
        String expected = c.expectedPoints() == null || c.expectedPoints().isEmpty()
                ? "（无，按常识判断）" : String.join("；", c.expectedPoints());
        String judgePrompt = """
                你是一个严格的 LLM 评估裁判，请按 0-10 分为下面的模型回答打分。
                评分维度：答案要点命中、期望工具是否使用、是否编造事实。
                10 分 = 完全符合期望；5 分 = 部分符合；0 分 = 完全不符合。

                题目：%s
                期望要点：%s
                期望工具：%s

                模型回答：
                ---
                %s
                ---

                只输出 JSON（不要 markdown 代码块、不要额外解释）：
                {"score": <0-10 整数>, "reasons": "<中文简要理由>"}
                """.formatted(c.prompt(), expected,
                c.expectedTool() == null ? "无" : c.expectedTool(), response);

        String judgeModel = evalModel();
        if (judgeModel.isBlank()) {
            judgeModel = defaultModel;
        }
        // R-06：judge 走 router（本地 Ollama 或 -Deval.model 指定云端），与 agent 同源
        String raw = llmProviderRouter.forUser("eval-user", judgeModel)
                .complete(ChatTurn.of(judgeModel,
                        List.of(ChatMessage.user(judgePrompt))))
                .block(Duration.ofSeconds(180));
        return parseJudge(raw);
    }

    private static int median(List<Integer> scores) {
        List<Integer> sorted = new ArrayList<>(scores);
        sorted.sort(Integer::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    private static String evalModel() {
        String v = System.getProperty("eval.model", "");
        return v == null ? "" : v.trim();
    }

    private static int evalSamples() {
        try {
            return Integer.parseInt(System.getProperty("eval.samples", "1"));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private JudgeOutput parseJudge(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JudgeOutput(null, "裁判无输出");
        }
        String cleaned = raw.trim();
        Matcher fence = JSON_FENCE.matcher(cleaned);
        if (fence.find()) {
            cleaned = fence.group(1).trim();
        }
        try {
            Map<?, ?> json = objectMapper.readValue(cleaned, Map.class);
            Object score = json.get("score");
            Object reasonsObj = json.get("reasons");
            String reasons = reasonsObj == null ? "" : String.valueOf(reasonsObj);
            if (score instanceof Number n) {
                return new JudgeOutput(n.intValue(), reasons);
            }
            return new JudgeOutput(null, "裁判 JSON 缺少 score: " + cleaned);
        } catch (Exception e) {
            Matcher m = SCORE_PATTERN.matcher(cleaned);
            if (m.find()) {
                return new JudgeOutput(Integer.parseInt(m.group(1)), "正则兜底解析: " + cleaned);
            }
            return new JudgeOutput(null, "裁判输出无法解析: " + raw);
        }
    }

    private List<EvalCase> loadCases() throws Exception {
        byte[] bytes = getClass().getClassLoader().getResourceAsStream("eval/golden-cases.json")
                .readAllBytes();
        List<EvalCase> all = List.of(objectMapper.readValue(bytes, EvalCase[].class));
        String filter = System.getProperty("eval.cases", "");
        if (filter == null || filter.isBlank()) {
            return all;
        }
        java.util.Set<String> wanted = java.util.Arrays.stream(filter.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        return all.stream().filter(c -> wanted.contains(c.id())).toList();
    }

    private record EvalCase(String id, String category, String prompt,
                            List<String> conversation, String userId, Boolean useTools,
                            String expectedTool, List<String> expectedPoints,
                            String sceneChatType, Boolean sceneMentioned, Double minScore) {
    }

    private record CaseResult(String id, String category, String prompt, String response,
                              String expectedTool, List<String> expectedPoints,
                              Integer score, String reasons, String status, long latencyMs,
                              String model) {
    }

    private record JudgeOutput(Integer score, String reasons) {
    }
}

package com.intelligent.agent.web.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.ai.llm.ChatMessage;
import com.intelligent.agent.web.ai.llm.ChatTurn;
import com.intelligent.agent.web.ai.llm.ollama.OllamaLlmProvider;
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
 * v1 不设分数门槛（CI 先跑 1-2 周基线）；如需临时门槛可传
 * {@code -Deval.min-score=7}，低于阈值的用例将使构建失败。</p>
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
    private OllamaLlmProvider ollamaLlmProvider;

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

        // 基线模式：只要求至少 1 个用例真实跑通（全挂说明环境/接线问题）
        assertThat(error).isLessThan(results.size());

        // 可选门槛：-Deval.min-score=7
        String minScoreProp = System.getProperty("eval.min-score");
        if (minScoreProp != null && !minScoreProp.isBlank()) {
            double min = Double.parseDouble(minScoreProp.trim());
            for (CaseResult r : results) {
                assertThat(r.score())
                        .as("用例 %s 得分 %.0f 低于门槛 %.0f：%s", r.id(),
                                r.score() == null ? -1 : r.score(), min, r.reasons())
                        .isNotNull()
                        .isGreaterThanOrEqualTo((int) Math.ceil(min));
            }
        }
    }

    private CaseResult runCase(EvalCase c) {
        long start = System.currentTimeMillis();
        try {
            ChatRequest req = new ChatRequest();
            req.setMessage(c.prompt());
            req.setUserId("eval:user");
            req.setUseTools(c.useTools() == null || c.useTools());
            req.setUseMemory(true);
            req.setChannel("web");
            req.setSceneChatType(c.sceneChatType());
            req.setSceneMentioned(c.sceneMentioned() != null && c.sceneMentioned());

            String response = String.valueOf(
                    agentService.chatFull(req).getOrDefault("response", ""));
            JudgeOutput judge = judge(c, response);
            return new CaseResult(c.id(), c.category(), c.prompt(), response,
                    c.expectedTool(), c.expectedPoints(), judge.score(), judge.reasons(),
                    "ok", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[eval] 用例 {} 执行失败: {}", c.id(), e.getMessage());
            return new CaseResult(c.id(), c.category(), c.prompt(), "",
                    c.expectedTool(), c.expectedPoints(), null, e.getMessage(),
                    "error", System.currentTimeMillis() - start);
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

        String raw = ollamaLlmProvider.complete(ChatTurn.of(defaultModel,
                        List.of(ChatMessage.user(judgePrompt))))
                .block(Duration.ofSeconds(180));
        return parseJudge(raw);
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
        return List.of(objectMapper.readValue(bytes, EvalCase[].class));
    }

    private record EvalCase(String id, String category, String prompt, Boolean useTools,
                            String expectedTool, List<String> expectedPoints,
                            String sceneChatType, Boolean sceneMentioned) {
    }

    private record CaseResult(String id, String category, String prompt, String response,
                              String expectedTool, List<String> expectedPoints,
                              Integer score, String reasons, String status, long latencyMs) {
    }

    private record JudgeOutput(Integer score, String reasons) {
    }
}

package com.intelligent.agent.web.ai.llm;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模型能力注册表（TD-10）：把"模型名 → 能力"的判断集中到一处。
 *
 * <p>此前同一批模型名关键词散在四处，接一个新模型要改四份代码：</p>
 * <ul>
 *   <li>{@code AgentConfig} / {@code PromptService}：text-tool 前缀（dolphin/phi2/orca-*）；</li>
 *   <li>{@code LlmVisionSupport}：视觉关键词（llava/gemini/claude-3/...）；</li>
 *   <li>{@code PromptService}：unrestricted 覆盖层（dolphin 无审查锚定）；</li>
 *   <li>{@code ContextBudget} / {@code OllamaLlmProvider}：num_ctx 配置表。</li>
 * </ul>
 *
 * <p>本类只做"配置 → 判定"，不持有 Spring 状态；由 {@code AgentConfig} 从
 * {@code ai.llm.*} 配置装配。默认值与历史行为保持一致，新增模型优先改配置
 * （{@code ai.llm.text-tool-patterns} / {@code ai.llm.vision-keywords} /
 * {@code ai.llm.vision-models} / {@code ai.llm.unrestricted-patterns} /
 * {@code ai.llm.ollama.num-ctx-by-model}）。</p>
 */
public final class ModelCapabilityRegistry {

    /** 不支持 Ollama 原生 Function Calling、需走文本工具解析的模型名片段。 */
    public static final List<String> DEFAULT_TEXT_TOOL_PATTERNS =
            List.of("dolphin", "phi2", "orca-mini", "orca2");

    /** 视觉模型名关键词（命中即视为支持图片理解）。 */
    public static final List<String> DEFAULT_VISION_KEYWORDS = List.of(
            "vl", "vision", "llava", "minicpm", "4o", "gpt-4-vision",
            "gemini", "claude-3", "qwen-vl", "glm-4v", "internvl",
            "bakllava", "moondream", "phi-3-vision", "llama3.2-vision",
            "llama3.2-v", "qwen2.5-vl");

    /** 需要追加"无审查"锚定语句的模型名片段。 */
    public static final List<String> DEFAULT_UNRESTRICTED_PATTERNS = List.of("dolphin");

    /**
     * 全局默认 num_ctx（与 application.yml 默认一致）。
     *
     * <p>不能用 4096：system prompt（含 soul/persona/记忆召回）实测约 4k token，
     * 22 个原生工具定义约 3.5k token，合计远超 4096，Ollama 会静默截断 prompt
     * （2026-11-03 memory-002/004 现场：记忆块被截掉，模型答非所问）。</p>
     *
     * <p>8192 也不够稳：再叠加一轮工具消息就逼近上限，Ollama 的上下文滑动会让
     * 模型输出断词残句（eval calc-002/tool-combo-001 现场）。12288 下这些用例稳定满分。</p>
     */
    public static final int DEFAULT_NUM_CTX = 12288;

    private final List<String> textToolPatterns;
    private final List<String> visionKeywords;
    private final List<String> visionModels;
    private final List<String> unrestrictedPatterns;
    private final int defaultNumCtx;
    private final Map<String, Integer> numCtxByModel;

    public ModelCapabilityRegistry(List<String> textToolPatterns, List<String> visionKeywords,
                                   List<String> visionModels, List<String> unrestrictedPatterns,
                                   int defaultNumCtx, Map<String, Integer> numCtxByModel) {
        this.textToolPatterns = cleanOrDefault(textToolPatterns, DEFAULT_TEXT_TOOL_PATTERNS);
        this.visionKeywords = cleanOrDefault(visionKeywords, DEFAULT_VISION_KEYWORDS);
        this.visionModels = cleanOrDefault(visionModels, List.of());
        this.unrestrictedPatterns =
                cleanOrDefault(unrestrictedPatterns, DEFAULT_UNRESTRICTED_PATTERNS);
        this.defaultNumCtx = defaultNumCtx > 0 ? defaultNumCtx : DEFAULT_NUM_CTX;
        this.numCtxByModel = numCtxByModel == null ? Map.of() : Map.copyOf(numCtxByModel);
    }

    /** 全部默认能力（只使用代码内置值，供单元测试 / 非 Spring 场景）。 */
    public static ModelCapabilityRegistry defaults() {
        return new ModelCapabilityRegistry(null, null, null, null, 0, null);
    }

    /** 只覆盖 text-tool 前缀（兼容旧构造器）。 */
    public static ModelCapabilityRegistry withTextToolPatterns(List<String> patterns) {
        return new ModelCapabilityRegistry(patterns, null, null, null, 0, null);
    }

    /** 只覆盖显式视觉模型列表（兼容 {@code ai.llm.vision-models} 旧接线）。 */
    public static ModelCapabilityRegistry withVisionModels(List<String> models) {
        return new ModelCapabilityRegistry(null, null, models, null, 0, null);
    }

    /** 非原生 Function Calling 模型：工具调用需走文本解析。 */
    public boolean supportsTextTools(String model) {
        return containsAny(model, textToolPatterns);
    }

    /** 视觉模型：显式登记名精确命中，或模型名包含任一视觉关键词。 */
    public boolean supportsVision(String model) {
        return supportsVision(model, visionModels);
    }

    /** 视觉模型，附加调用方提供的显式模型名列表（兼容旧 API）。 */
    public boolean supportsVision(String model, List<String> extraModels) {
        String normalized = normalize(model);
        if (normalized.isEmpty()) {
            return false;
        }
        if (matchesAnyExactly(normalized, visionModels)
                || matchesAnyExactly(normalized, extraModels)) {
            return true;
        }
        return containsAny(normalized, visionKeywords);
    }

    /** 需要追加 unrestricted（无审查）覆盖层的模型。 */
    public boolean needsUnrestrictedOverlay(String model) {
        return containsAny(model, unrestrictedPatterns);
    }

    /** num_ctx：模型配置表优先，未命中回退全局默认（请求显式值由 ContextBudget 先行处理）。 */
    public int numCtxFor(String model) {
        if (model != null) {
            Integer configured = numCtxByModel.get(model.trim());
            if (configured != null && configured > 0) {
                return configured;
            }
        }
        return defaultNumCtx;
    }

    public int defaultNumCtx() {
        return defaultNumCtx;
    }

    public Map<String, Integer> numCtxByModel() {
        return numCtxByModel;
    }

    public List<String> textToolPatterns() {
        return textToolPatterns;
    }

    public List<String> visionKeywords() {
        return visionKeywords;
    }

    public List<String> visionModels() {
        return visionModels;
    }

    public List<String> unrestrictedPatterns() {
        return unrestrictedPatterns;
    }

    private static List<String> cleanOrDefault(List<String> values, List<String> defaults) {
        if (values == null || values.isEmpty()) {
            return defaults;
        }
        List<String> cleaned = values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .toList();
        return cleaned.isEmpty() ? defaults : cleaned;
    }

    private static boolean containsAny(String model, List<String> patterns) {
        String normalized = normalize(model);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (normalized.contains(normalize(pattern))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyExactly(String normalizedModel, List<String> names) {
        for (String name : names) {
            if (name != null && !name.isBlank()
                    && normalizedModel.equals(normalize(name))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

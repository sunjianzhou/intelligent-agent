package com.intelligent.agent.web.ai.memory;

import com.intelligent.agent.web.ai.llm.ChatMessage;

import java.util.List;
import java.util.Map;

/**
 * 上下文 token 预算（R-01）：num_ctx 的唯一来源。
 *
 * <p>消除三套数字（PromptService maxContextTokens / 前端 CTX_LIMIT / Ollama num_ctx）不一致的问题：
 * 所有分块（system / 工具 / 记忆召回 / 项目上下文 / 历史 / 当前消息）的预算都由本组件
 * 从 num_ctx 派生。num_ctx 优先级与 {@code OllamaLlmProvider} 完全一致：
 * 请求显式指定 &gt; 模型配置表 &gt; 全局默认。</p>
 *
 * <p>token 估算为主路径字符规则（O(1) 无额外 HTTP）：CJK≈1 token/字、其余≈0.25 token/字符；
 * 每条消息另计固定格式开销。精确计数（Ollama）仅作可选校验，不引入请求往返。</p>
 */
public class ContextBudget {

    /** 安全边际：估算误差 + 模型输出预留。实际请求体最多使用 num_ctx 的 (1 - 15%)。 */
    public static final double SAFETY_MARGIN = 0.15;

    /** 各分块占可用预算（usable = num_ctx * (1 - margin)）的比例，合计 = 1.0。 */
    public static final double SYSTEM_RATIO = 0.25;
    public static final double TOOLS_RATIO = 0.10;
    public static final double MEMORY_RATIO = 0.10;
    public static final double PROJECT_RATIO = 0.05;
    public static final double HISTORY_RATIO = 0.45;
    public static final double CURRENT_RATIO = 0.05;

    /** 每条消息的固定格式开销（role / 结构 / 分隔符）。 */
    public static final int MESSAGE_OVERHEAD_TOKENS = 4;

    private final int defaultNumCtx;
    private final Map<String, Integer> numCtxByModel;

    public ContextBudget(int defaultNumCtx, Map<String, Integer> numCtxByModel) {
        this.defaultNumCtx = defaultNumCtx > 0 ? defaultNumCtx : 4096;
        this.numCtxByModel = numCtxByModel == null ? Map.of() : Map.copyOf(numCtxByModel);
    }

    /**
     * 解析模型 num_ctx：请求显式指定（options.num_ctx）&gt; 模型配置表 &gt; 全局默认。
     * 与 {@code OllamaLlmProvider.resolveNumCtx} 优先级一致，保证预算与真实请求同源。
     */
    public int numCtxFor(String model, Map<String, Object> requestOptions) {
        Object ctxRaw = requestOptions == null ? null : requestOptions.get("num_ctx");
        if (ctxRaw != null) {
            try {
                int explicit = ctxRaw instanceof Number n
                        ? n.intValue()
                        : Integer.parseInt(String.valueOf(ctxRaw).trim());
                if (explicit > 0) {
                    return explicit;
                }
            } catch (NumberFormatException ignored) {
                // 非法值回退到模型配置表 / 默认
            }
        }
        if (model != null && numCtxByModel.containsKey(model)) {
            return numCtxByModel.get(model);
        }
        return defaultNumCtx;
    }

    /** 可用预算：num_ctx 扣除安全边际。 */
    public int usableTokens(int numCtx) {
        return Math.max(1, (int) Math.floor(numCtx * (1.0 - SAFETY_MARGIN)));
    }

    /** 计算某模型 + 请求选项下的完整分块预算。 */
    public Plan plan(String model, Map<String, Object> requestOptions) {
        int numCtx = numCtxFor(model, requestOptions);
        int usable = usableTokens(numCtx);
        return new Plan(numCtx, usable,
                block(usable, SYSTEM_RATIO),
                block(usable, TOOLS_RATIO),
                block(usable, MEMORY_RATIO),
                block(usable, PROJECT_RATIO),
                block(usable, HISTORY_RATIO),
                block(usable, CURRENT_RATIO));
    }

    /** token 估算：CJK≈1 token/字，其余≈0.25 token/字符（向上取整）。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                cjk++;
            }
            i += Character.charCount(cp);
        }
        int nonCjk = text.length() - cjk;
        return cjk + (int) Math.ceil(nonCjk * 0.25);
    }

    /** 单条消息估算：固定开销 + role/content 字符规则。 */
    public static int estimateMessage(ChatMessage message) {
        if (message == null) {
            return MESSAGE_OVERHEAD_TOKENS;
        }
        return MESSAGE_OVERHEAD_TOKENS
                + estimateTokens(message.role() + "\n" + message.content());
    }

    /** 消息列表估算（用于历史 / 系统块）。 */
    public static int estimateMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            total += estimateMessage(message);
        }
        return total;
    }

    /**
     * 记忆召回按预算裁剪：按相关性顺序保留尽可能多的记录；
     * 单条超预算时截断内容并附标记（宁可保留关键前缀，不整条丢弃）。
     */
    public static List<MemoryRecord> fitRecords(List<MemoryRecord> records, int budget) {
        if (records == null || records.isEmpty() || budget <= 0) {
            return List.of();
        }
        List<MemoryRecord> out = new java.util.ArrayList<>();
        int used = 0;
        for (MemoryRecord record : records) {
            int tokens = estimateTokens(record.content());
            if (!out.isEmpty() && used + tokens > budget) {
                break;
            }
            if (used + tokens > budget) {
                String fit = fitToBudget(record.content(), budget - used);
                if (!fit.isBlank()) {
                    out.add(new MemoryRecord(record.id(), record.userId(), fit,
                            record.roleId(), record.projectId(), record.type(),
                            record.metadata(), record.importance()));
                }
                break;
            }
            out.add(record);
            used += tokens;
        }
        return out;
    }

    /**
     * 把文本截断到预算内（返回最大前缀，附截断标记）。
     * 二分查找使估算单调递减，O(log n) 次估算。
     */
    public static String fitToBudget(String text, int budget) {
        if (text == null || text.isBlank() || budget <= 0) {
            return "";
        }
        if (estimateTokens(text) <= budget) {
            return text;
        }
        String marker = "\n…(内容已按上下文预算截断)";
        int markerTokens = estimateTokens(marker);
        int contentBudget = Math.max(1, budget - markerTokens);
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (estimateTokens(text.substring(0, mid)) <= contentBudget) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo).stripTrailing() + marker;
    }

    private static int block(int usable, double ratio) {
        return Math.max(1, (int) Math.floor(usable * ratio));
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)      // CJK 统一表意文字
                || (cp >= 0x3040 && cp <= 0x30FF)  // 平假名 / 片假名
                || (cp >= 0xAC00 && cp <= 0xD7AF); // 谚文音节
    }

    /** 一次请求的分块预算结构。 */
    public record Plan(
            int numCtx,
            int usableTokens,
            int systemTokens,
            int toolTokens,
            int memoryTokens,
            int projectTokens,
            int historyTokens,
            int currentTokens) {

        public Plan {
            numCtx = Math.max(1, numCtx);
            usableTokens = Math.max(1, usableTokens);
            systemTokens = Math.max(1, systemTokens);
            toolTokens = Math.max(1, toolTokens);
            memoryTokens = Math.max(1, memoryTokens);
            projectTokens = Math.max(1, projectTokens);
            historyTokens = Math.max(1, historyTokens);
            currentTokens = Math.max(1, currentTokens);
        }
    }
}

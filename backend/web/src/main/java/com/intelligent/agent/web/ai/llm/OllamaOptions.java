package com.intelligent.agent.web.ai.llm;

/**
 * Ollama 推理参数（与 Python 侧 settings 的 ollama_* 配置一一对应）。
 *
 * @param temperature    采样温度
 * @param maxTokens      最大生成 token 数（num_predict）
 * @param topP           核采样阈值
 * @param topK           候选 token 数
 * @param repeatPenalty  重复惩罚
 * @param numCtx         上下文窗口
 * @param numGpu         GPU 层数；-1 = Ollama 自动
 * @param keepAlive      模型常驻时长（"-1" = 永久常驻）
 */
public record OllamaOptions(
        double temperature,
        int maxTokens,
        double topP,
        int topK,
        double repeatPenalty,
        int numCtx,
        int numGpu,
        String keepAlive) {

    public static OllamaOptions defaults() {
        return new OllamaOptions(0.7, 2048, 0.9, 40, 1.1, 4096, -1, "-1");
    }
}

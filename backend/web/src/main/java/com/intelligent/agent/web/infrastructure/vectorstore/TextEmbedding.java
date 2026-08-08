package com.intelligent.agent.web.infrastructure.vectorstore;

/**
 * 轻量字符 n-gram 哈希嵌入工具。
 * <p>
 * 无需外部模型即可提供确定性的语义相似度（余弦），供内存向量仓库与
 * 语义响应缓存复用；后续可替换为真实嵌入模型而无需改动调用方。
 */
public final class TextEmbedding {

    public static final int DIMENSION = 128;

    private TextEmbedding() {
    }

    public static double[] embed(String text) {
        double[] vector = new double[DIMENSION];
        String normalized = text == null ? ""
                : text.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fff ]", " ").trim();
        if (normalized.isEmpty()) {
            return vector;
        }

        StringBuilder grams = new StringBuilder();
        for (String token : normalized.split("\\s+")) {
            String padded = "  " + token + "  ";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                grams.append(padded, i, i + 3).append('|');
            }
        }
        String gramText = grams.toString();
        boolean any = false;
        for (int i = 0; i + 3 <= gramText.length(); i += 3) {
            int hash = hash(gramText.substring(i, i + 3));
            vector[Math.floorMod(hash, DIMENSION)] += 1.0;
            vector[Math.floorMod(hash * 31 + 7, DIMENSION)] += 0.5;
            any = true;
        }
        if (!any) {
            int hash = hash(normalized);
            vector[Math.floorMod(hash, DIMENSION)] += 1.0;
        }
        normalize(vector);
        return vector;
    }

    public static double cosine(double[] a, double[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    private static int hash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }

    private static void normalize(double[] vector) {
        double norm = 0.0;
        for (double v : vector) {
            norm += v * v;
        }
        if (norm == 0.0) {
            return;
        }
        double inv = 1.0 / Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= inv;
        }
    }
}

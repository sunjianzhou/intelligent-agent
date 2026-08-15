package com.intelligent.agent.web.domain.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库分块契约（2026-08-15 补充）：段落/句子边界分块 + overlap，
 * 单块不超过上限，长文本被正确切分。
 */
class KnowledgeChunkingTest {

    private static final int CHUNK_SIZE = 800;
    private static final int OVERLAP = 100;

    @Test
    void emptyTextYieldsNoChunks() {
        assertThat(KnowledgeService.chunkText("   ")).isEmpty();
    }

    @Test
    void shortTextYieldsSingleChunk() {
        List<String> chunks = KnowledgeService.chunkText("这是一段简短的知识内容。");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("这是一段简短的知识内容。");
    }

    @Test
    void longTextIsSplitAndRespectsSizeLimit() {
        String paragraph = "句".repeat(1500) + "。";
        List<String> chunks = KnowledgeService.chunkText(paragraph);

        assertThat(chunks.size()).isGreaterThan(1);
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(CHUNK_SIZE + OVERLAP);
            assertThat(chunk).isNotBlank();
        }
        // 内容拼接后应覆盖原文（顺序拼接非空块）
        StringBuilder joined = new StringBuilder();
        for (String chunk : chunks) {
            joined.append(chunk);
        }
        assertThat(joined.length()).isGreaterThanOrEqualTo(1500);
    }

    @Test
    void paragraphBoundariesArePreserved() {
        String text = "第一段内容。\n\n第二段内容。\n\n第三段内容。";
        List<String> chunks = KnowledgeService.chunkText(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("第一段内容").contains("第二段内容");
    }
}

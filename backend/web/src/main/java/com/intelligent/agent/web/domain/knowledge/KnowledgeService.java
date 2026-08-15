package com.intelligent.agent.web.domain.knowledge;

import com.intelligent.agent.web.ai.memory.MemoryRecord;
import com.intelligent.agent.web.ai.memory.MemoryRepository;
import com.intelligent.agent.web.ai.memory.MemorySearchQuery;
import com.intelligent.agent.web.domain.InvalidRequestException;
import com.intelligent.agent.web.domain.NotFoundException;
import com.intelligent.agent.web.domain.PayloadTooLargeException;
import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 知识文件入库领域服务（Plan 2 / Task 4）。
 * <ul>
 *   <li>支持 .txt / .md / .json / .pdf，最大 10MB；</li>
 *   <li>按段落→句子边界分块（chunk 800 / overlap 100），写入
 *       {@link MemoryRepository}（type=knowledge，file_id 过滤）；</li>
 *   <li>文件清单 → data/knowledge_files/{user_id}.json。</li>
 * </ul>
 */
@Slf4j
public class KnowledgeService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int CHUNK_SIZE = 800;
    private static final int CHUNK_OVERLAP = 100;
    private static final List<String> ALLOWED_EXT = List.of(".txt", ".md", ".pdf", ".json");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？.!?])\\s*");

    private final JsonFileStore store;
    private final MemoryRepository memoryRepository;

    public KnowledgeService(Path dataDir, MemoryRepository memoryRepository) {
        this.store = new JsonFileStore(dataDir);
        this.memoryRepository = memoryRepository;
    }

    public Map<String, Object> upload(String userId, String filename, byte[] content, String description) {
        String safeName = filename == null || filename.isBlank() ? "unknown" : filename;
        String ext = extOf(safeName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new InvalidRequestException(
                    "不支持 " + ext + "，可用: " + String.join(", ", ALLOWED_EXT));
        }
        if (content == null || content.length > MAX_BYTES) {
            throw new PayloadTooLargeException("文件过大，最大允许 10MB");
        }

        String text;
        try {
            text = extractText(safeName, content);
        } catch (InvalidRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidRequestException("文件解析失败: " + e.getMessage());
        }
        if (text == null || text.isBlank()) {
            throw new InvalidRequestException("文件内容为空，无法入库");
        }

        List<String> chunks = chunkText(text);
        String fileId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        List<String> chunkIds = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = "kf_" + fileId.replace("-", "") + "_" + i;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "file");
            metadata.put("filename", safeName);
            metadata.put("file_id", fileId);
            metadata.put("chunk_index", i);
            metadata.put("category", "knowledge");
            metadata.put("description", description == null ? "" : description);
            memoryRepository.upsert(new MemoryRecord(
                    chunkId, userId, chunks.get(i), null, null, "knowledge",
                    metadata, 0.7));
            chunkIds.add(chunkId);
        }

        Map<String, Object> manifest = manifest(userId);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("file_id", fileId);
        entry.put("filename", safeName);
        entry.put("uploaded_at", now);
        entry.put("chunk_count", chunks.size());
        entry.put("chunk_ids", chunkIds);
        entry.put("description", description == null ? "" : description);
        entry.put("size_bytes", content.length);
        entry.put("char_count", text.length());
        manifest.put(fileId, entry);
        store.write(new String[]{"knowledge_files", userId + ".json"}, manifest);

        log.info("文件入库完成: user={}, file={}, chunks={}", userId, safeName, chunks.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("file_id", fileId);
        result.put("filename", safeName);
        result.put("chunk_count", chunks.size());
        result.put("char_count", text.length());
        return result;
    }

    public Map<String, Object> listFiles(String userId) {
        Map<String, Object> manifest = manifest(userId);
        List<Map<String, Object>> files = new ArrayList<>();
        for (Object value : manifest.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) value;
            files.add(entry);
        }
        files.sort((a, b) -> String.valueOf(b.get("uploaded_at"))
                .compareTo(String.valueOf(a.get("uploaded_at"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("files", files);
        result.put("count", files.size());
        return result;
    }

    public Map<String, Object> deleteFile(String userId, String fileId) {
        Map<String, Object> manifest = manifest(userId);
        Object entryObj = manifest.get(fileId);
        if (entryObj == null) {
            throw new NotFoundException("文件不存在");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) entryObj;
        int deletedChunks = 0;
        for (Object chunkIdObj : listOf(entry.get("chunk_ids"))) {
            if (memoryRepository.delete(userId, String.valueOf(chunkIdObj))) {
                deletedChunks++;
            }
        }
        manifest.remove(fileId);
        store.write(new String[]{"knowledge_files", userId + ".json"}, manifest);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("file_id", fileId);
        result.put("deleted_chunks", deletedChunks);
        return result;
    }

    // ── 文本提取 / 分块 ───────────────────────────────────────

    private static String extractText(String filename, byte[] content) throws Exception {
        String ext = extOf(filename);
        if (ext.equals(".txt") || ext.equals(".md")) {
            return new String(content, StandardCharsets.UTF_8);
        }
        if (ext.equals(".json")) {
            String raw = new String(content, StandardCharsets.UTF_8);
            try {
                Object parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(raw, Object.class);
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
            } catch (Exception e) {
                return raw;
            }
        }
        if (ext.equals(".pdf")) {
            try (org.apache.pdfbox.pdmodel.PDDocument doc =
                         org.apache.pdfbox.pdmodel.PDDocument.load(content)) {
                return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            }
        }
        throw new InvalidRequestException("不支持的文件类型 " + ext);
    }

    static List<String> chunkText(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] paragraphs = trimmed.split("\n\\s*\n");
        for (String paragraph : paragraphs) {
            String para = paragraph.trim();
            if (para.isEmpty()) {
                continue;
            }
            if (para.length() > CHUNK_SIZE) {
                for (String sentence : SENTENCE_SPLIT.split(para)) {
                    if (sentence.isBlank()) {
                        continue;
                    }
                    if (sentence.length() > CHUNK_SIZE) {
                        // 2026-08-15：超长单句（无标点长文本）硬切分，避免单块超上下文预算
                        if (current.length() > 0) {
                            chunks.add(current.toString().trim());
                            current = new StringBuilder();
                        }
                        for (int pos = 0; pos < sentence.length(); pos += CHUNK_SIZE) {
                            chunks.add(sentence.substring(pos,
                                    Math.min(sentence.length(), pos + CHUNK_SIZE)));
                        }
                    } else if (current.length() + sentence.length() <= CHUNK_SIZE) {
                        current.append(sentence);
                    } else {
                        if (current.length() > 0) {
                            chunks.add(current.toString().trim());
                        }
                        current = new StringBuilder(sentence);
                    }
                }
            } else {
                if (current.length() + para.length() + 2 <= CHUNK_SIZE) {
                    if (current.length() > 0) {
                        current.append("\n\n");
                    }
                    current.append(para);
                } else {
                    if (current.length() > 0) {
                        chunks.add(current.toString().trim());
                    }
                    current = new StringBuilder(para);
                }
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        if (CHUNK_OVERLAP > 0 && chunks.size() > 1) {
            List<String> overlapped = new ArrayList<>();
            overlapped.add(chunks.get(0));
            for (int i = 1; i < chunks.size(); i++) {
                String prev = chunks.get(i - 1);
                String tail = prev.length() >= CHUNK_OVERLAP
                        ? prev.substring(prev.length() - CHUNK_OVERLAP) : prev;
                overlapped.add(tail + chunks.get(i));
            }
            return overlapped;
        }
        return chunks;
    }

    private Map<String, Object> manifest(String userId) {
        Map<String, Object> manifest = store.read("knowledge_files", userId + ".json");
        return manifest == null ? new LinkedHashMap<>() : manifest;
    }

    private static String extOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }
}

package com.intelligent.agent.web.ai.prompt;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 灵魂文件加载器（对齐 Python SoulLoader v1.1）。
 *
 * <p>从 soul/ 目录加载必选文件（SOUL/USER/MEMORY/IDENTITY/HEARTBEAT）与
 * 可选文件（whisper/heart/rules）。单个文件或总量超阈值时仅告警不阻断，
 * 内容从不硬截断。支持 {@link #reload()} 热重载（rules 写入后调用）。</p>
 */
@Slf4j
public class SoulLoader {

    private static final int DEFAULT_MAX_FILE_SIZE = 50_000;
    private static final int DEFAULT_MAX_TOTAL_CHARS = 14_000;

    private static final List<String> REQUIRED = List.of("SOUL", "USER", "MEMORY", "IDENTITY", "HEARTBEAT");
    private static final List<String> OPTIONAL = List.of("whisper", "heart", "rules");

    private final Path soulDir;
    private final int maxFileSize;
    private final int maxTotalChars;

    private volatile SoulData data;
    /** 内容版本号：reload() 时自增，供 PromptService 静态预拼接缓存做变更检测。 */
    private final AtomicLong version = new AtomicLong();

    public SoulLoader(Path soulDir) {
        this(soulDir, DEFAULT_MAX_FILE_SIZE, DEFAULT_MAX_TOTAL_CHARS);
    }

    public SoulLoader(Path soulDir, int maxFileSize, int maxTotalChars) {
        this.soulDir = Objects.requireNonNull(soulDir, "soulDir must not be null").toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
        this.maxTotalChars = maxTotalChars;
        this.data = load();
    }

    public Path soulDir() {
        return soulDir;
    }

    public SoulData data() {
        return data;
    }

    public long version() {
        return version.get();
    }

    /** 热重载：运行时调用，无需重启服务。 */
    public synchronized SoulData reload() {
        this.data = load();
        this.version.incrementAndGet();
        return data;
    }

    private SoulData load() {
        Map<String, String> parts = new LinkedHashMap<>();
        Map<String, Integer> fileSizes = new LinkedHashMap<>();
        List<String> oversized = new java.util.ArrayList<>();
        List<String> missing = new java.util.ArrayList<>();

        if (!Files.isDirectory(soulDir)) {
            log.warn("soul 目录不存在，使用空灵魂数据: {}", soulDir);
            return SoulData.empty();
        }

        for (String name : REQUIRED) {
            Path path = soulDir.resolve(name + ".md");
            if (!Files.exists(path)) {
                missing.add(path.toString());
                parts.put(name.toLowerCase(), "");
                fileSizes.put(name.toLowerCase(), 0);
                continue;
            }
            String content = readText(path);
            fileSizes.put(name.toLowerCase(), content.length());
            if (content.length() > maxFileSize) {
                oversized.add(name + ".md (" + content.length() + " chars > " + maxFileSize + " limit)");
            }
            parts.put(name.toLowerCase(), content);
        }

        if (!missing.isEmpty()) {
            log.warn("必选灵魂文件缺失（使用空内容）: {}", String.join(", ", missing));
        }

        for (String name : OPTIONAL) {
            Path path = soulDir.resolve(name + ".md");
            if (Files.exists(path)) {
                String content = readText(path);
                fileSizes.put(name, content.length());
                if (content.length() > maxFileSize) {
                    oversized.add(name + ".md (" + content.length() + " chars > " + maxFileSize + " limit)");
                }
                parts.put(name, content);
            } else {
                parts.put(name, "");
                fileSizes.put(name, 0);
            }
        }

        int totalChars = fileSizes.values().stream().mapToInt(Integer::intValue).sum();

        if (!oversized.isEmpty()) {
            log.warn("灵魂文件超大（>{} chars）: {}. 建议精简内容或调高 max_file_size 参数。",
                    maxFileSize, String.join(", ", oversized));
        }
        if (totalChars > maxTotalChars) {
            log.warn("灵魂层总内容 {} chars 超过告警阈值 {} chars，可能挤压对话消息空间。",
                    totalChars, maxTotalChars);
        }
        if (totalChars > 0) {
            log.info("灵魂加载成功: {} chars total", totalChars);
        } else {
            log.info("灵魂加载成功（所有文件均为空）");
        }

        return new SoulData(
                parts.get("soul"),
                parts.get("user"),
                parts.get("memory"),
                parts.get("identity"),
                parts.get("heartbeat"),
                parts.get("whisper"),
                parts.get("heart"),
                parts.get("rules"),
                totalChars,
                fileSizes);
    }

    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取灵魂文件失败 " + path + ": " + e.getMessage(), e);
        }
    }
}

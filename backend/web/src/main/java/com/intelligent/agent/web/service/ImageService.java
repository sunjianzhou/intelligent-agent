package com.intelligent.agent.web.service;

import com.intelligent.agent.web.integration.comfyui.ComfyUiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 图片生成本地服务（TODO-110 Task 2）：
 * provider-status / models / switch-model / generate / progress / images 列表与删除。
 * 当前 provider 为 comfyui（默认工作流 + HTTP 轮询）；输出目录 data/images，5GB 自动清理。
 */
@Slf4j
@Service
public class ImageService {

    private static final double MAX_GALLERY_GB = 5.0;
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[\\w\\-]+\\.(png|jpg|jpeg|webp)$");
    private static final int MAX_HISTORY_POLLS = 60;

    @Value("${image.gen.provider:comfyui}")
    private String provider;

    @Value("${image.gen.base-url:http://localhost:8188}")
    private String baseUrl;

    @Value("${image.gen.model:}")
    private String model;

    @Value("${intelligent-agent.data-dir:data}")
    private String dataDir;

    private final ComfyUiClient comfyUiClient;

    public ImageService(ComfyUiClient comfyUiClient) {
        this.comfyUiClient = comfyUiClient;
    }

    public Map<String, Object> providerStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("provider", provider);
        result.put("model", model);
        result.put("base_url", baseUrl);
        try {
            comfyUiClient.systemStats();
            result.put("available", true);
            result.put("message", "服务正常");
        } catch (Exception e) {
            result.put("available", false);
            result.put("message", "服务不可达，请确认本地服务已启动");
        }
        return result;
    }

    public Map<String, Object> listModels() {
        List<String> models = comfyUiClient.listModels();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("provider", provider);
        result.put("models", models);
        result.put("current", model);
        return result;
    }

    public Map<String, Object> switchModel(String modelName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("model", modelName);
        result.put("message", "Provider '" + provider + "' 不支持运行时换模型（工作流中指定）");
        return result;
    }

    public Map<String, Object> generate(String prompt, String negativePrompt, int width, int height,
                                        int steps, double cfg, int seed) {
        try {
            String promptId = comfyUiClient.submitPrompt(ComfyUiClient.defaultTxt2ImgWorkflow(
                    prompt, negativePrompt, model == null || model.isBlank() ? "model.safetensors" : model,
                    width, height, steps, cfg, seed));
            String imageFile = pollForImage(promptId);
            if (imageFile == null) {
                return Map.of("success", false, "message", "生成超时或未产出图片");
            }
            enforceGalleryLimit();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("image_url", "/api/images/" + imageFile);
            result.put("filename", imageFile);
            result.put("provider", provider);
            result.put("prompt_id", promptId);
            return result;
        } catch (Exception e) {
            log.error("图片生成失败: {}", e.getMessage());
            return Map.of("success", false, "message", "图片生成失败: " + e.getMessage());
        }
    }

    public Map<String, Object> progress() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("provider", provider);
        result.put("progress", 0.0);
        result.put("eta", 0.0);
        return result;
    }

    public Map<String, Object> listImages() {
        List<Map<String, Object>> images = new ArrayList<>();
        for (Path file : listImageFiles()) {
            try {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("filename", file.getFileName().toString());
                item.put("size_bytes", Files.size(file));
                item.put("created_at", Files.getLastModifiedTime(file).toString());
                images.add(item);
            } catch (IOException e) {
                log.warn("读取图片元数据失败: {}", e.getMessage());
            }
        }
        images.sort((a, b) -> String.valueOf(b.get("created_at"))
                .compareTo(String.valueOf(a.get("created_at"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("images", images);
        result.put("count", images.size());
        return result;
    }

    public Map<String, Object> deleteImage(String filename) {
        if (!SAFE_FILENAME.matcher(filename).matches()) {
            return Map.of("success", false, "message", "非法文件名");
        }
        Path file = imageDir().resolve(filename);
        boolean deleted = false;
        try {
            deleted = Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除图片失败: {}", e.getMessage());
        }
        return Map.of("success", deleted, "file_id", filename,
                "message", deleted ? "已删除" : "图片不存在");
    }

    public Path resolveImage(String filename) {
        if (!SAFE_FILENAME.matcher(filename).matches()) {
            return null;
        }
        Path file = imageDir().resolve(filename);
        return Files.exists(file) ? file : null;
    }

    private String pollForImage(String promptId) throws InterruptedException, IOException {
        for (int i = 0; i < MAX_HISTORY_POLLS; i++) {
            Map<String, Object> history = comfyUiClient.history(promptId);
            Object entry = history.get(promptId);
            if (entry instanceof Map) {
                Object outputs = ((Map<?, ?>) entry).get("outputs");
                if (outputs instanceof Map) {
                    for (Object nodeOutput : ((Map<?, ?>) outputs).values()) {
                        if (nodeOutput instanceof Map) {
                            Object images = ((Map<?, ?>) nodeOutput).get("images");
                            if (images instanceof List && !((List<?>) images).isEmpty()) {
                                Object first = ((List<?>) images).get(0);
                                if (first instanceof Map) {
                                    String filename = String.valueOf(
                                            ((Map<?, ?>) first).get("filename"));
                                    byte[] bytes = comfyUiClient.viewImage(filename);
                                    if (bytes.length > 0) {
                                        String safeName = "gen_" + UUID.randomUUID()
                                                .toString().replace("-", "").substring(0, 10)
                                                + "_" + filename;
                                        Files.write(imageDir().resolve(safeName), bytes);
                                        return safeName;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Thread.sleep(1000);
        }
        return null;
    }

    private void enforceGalleryLimit() {
        try {
            long totalBytes = 0;
            List<Path> files = listImageFiles();
            for (Path file : files) {
                totalBytes += Files.size(file);
            }
            double totalGb = totalBytes / (1024.0 * 1024 * 1024);
            if (totalGb > MAX_GALLERY_GB) {
                files.sort(Comparator.comparing(
                        p -> {
                            try {
                                return Files.getLastModifiedTime(p).toMillis();
                            } catch (IOException e) {
                                return Long.MAX_VALUE;
                            }
                        }));
                while (totalGb > MAX_GALLERY_GB && !files.isEmpty()) {
                    Path oldest = files.remove(0);
                    totalGb -= Files.size(oldest) / (1024.0 * 1024 * 1024);
                    Files.deleteIfExists(oldest);
                }
                log.info("图片目录超限，已清理最旧文件");
            }
        } catch (IOException e) {
            log.warn("图片目录清理失败: {}", e.getMessage());
        }
    }

    private Path imageDir() {
        Path dir = Path.of(dataDir).resolve("images");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建图片目录", e);
        }
        return dir;
    }

    private List<Path> listImageFiles() {
        Path dir = imageDir();
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> SAFE_FILENAME.matcher(p.getFileName().toString()).matches())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}

package com.intelligent.agent.web.service;

import com.intelligent.agent.web.integration.comfyui.ComfyUiClient;
import com.intelligent.agent.web.infrastructure.filesystem.JsonFileStore;
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
    private static final String CUSTOM_WORKFLOW_FILE = "comfyui-workflow.json";

    @Value("${image.gen.provider:comfyui}")
    private String provider;

    @Value("${image.gen.base-url:http://localhost:8188}")
    private String baseUrl;

    @Value("${image.gen.model:}")
    private String model;

    @Value("${intelligent-agent.data-dir:data}")
    private String dataDir;

    private final ComfyUiClient comfyUiClient;
    private volatile JsonFileStore workflowStore;

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
        if (modelName == null || modelName.isBlank()) {
            return Map.of("success", false, "model", "",
                    "message", "模型名不能为空");
        }
        try {
            List<String> available = comfyUiClient.listModels();
            if (!available.isEmpty() && !available.contains(modelName)) {
                return Map.of("success", false, "model", modelName,
                        "message", "模型不在 ComfyUI 列表中");
            }
        } catch (Exception e) {
            // ComfyUI 不可达时允许切换，下次生成再报错
            log.warn("切换模型前查询列表失败，继续切换: {}", e.getMessage());
        }
        this.model = modelName;
        return Map.of("success", true, "model", modelName, "message", "已切换，下次生成生效");
    }

    public Map<String, Object> generate(String prompt, String negativePrompt, int width, int height,
                                        int steps, double cfg, int seed) {
        return generate(prompt, negativePrompt, width, height, steps, cfg, seed,
                null, null, null);
    }

    /**
     * 生成入口：模型自动匹配模板（SD15/SDXL/FLUX），支持 LoRA 注入与自定义工作流。
     * modelOverride 为空时用当前默认模型；custom 工作流存在时按 {{placeholder}} 替换后提交。
     */
    public Map<String, Object> generate(String prompt, String negativePrompt, int width, int height,
                                        int steps, double cfg, int seed,
                                        String modelOverride, String sampler,
                                        List<ComfyUiClient.Lora> loras) {
        try {
            String effectiveModel = (modelOverride == null || modelOverride.isBlank())
                    ? (model == null || model.isBlank() ? "model.safetensors" : model)
                    : modelOverride;
            String workflowKind = "sd15";
            Map<String, Object> workflow;
            Map<String, Object> custom = workflowStore().read(CUSTOM_WORKFLOW_FILE);
            if (custom != null && !custom.isEmpty()) {
                workflowKind = "custom";
                workflow = substitutePlaceholders(custom, prompt, negativePrompt, effectiveModel,
                        width, height, steps, cfg, seed);
            } else {
                switch (ComfyUiClient.ModelKind.detect(effectiveModel)) {
                    case FLUX -> {
                        workflowKind = "flux";
                        workflow = ComfyUiClient.fluxTxt2ImgWorkflow(
                                prompt, negativePrompt, effectiveModel, width, height,
                                steps, cfg, seed, sampler, loras);
                    }
                    case SDXL -> {
                        workflowKind = "sdxl";
                        workflow = ComfyUiClient.sdxlTxt2ImgWorkflow(
                                prompt, negativePrompt, effectiveModel, width, height,
                                steps, cfg, seed, sampler, loras);
                    }
                    default -> {
                        workflow = ComfyUiClient.txt2ImgWorkflow(
                                prompt, negativePrompt, effectiveModel, width, height,
                                steps, cfg, seed, sampler, loras);
                    }
                }
            }
            String promptId = comfyUiClient.submitPrompt(workflow);
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
            result.put("model", effectiveModel);
            result.put("workflow_kind", workflowKind);
            return result;
        } catch (Exception e) {
            log.error("图片生成失败: {}", e.getMessage());
            return Map.of("success", false, "message", "图片生成失败: " + e.getMessage());
        }
    }

    public Map<String, Object> listLoras() {
        List<String> loras = comfyUiClient.listLoras();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("provider", provider);
        result.put("loras", loras);
        result.put("count", loras.size());
        return result;
    }

    /** 当前自定义工作流（null = 使用内置模板）。 */
    public Map<String, Object> getCustomWorkflow() {
        Map<String, Object> custom = workflowStore().read(CUSTOM_WORKFLOW_FILE);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("using_custom", custom != null && !custom.isEmpty());
        result.put("workflow", custom);
        return result;
    }

    /** 保存自定义工作流模板（节点图，支持 {{prompt}}/{{model}}/{{width}} 等占位符）。 */
    public Map<String, Object> saveCustomWorkflow(Map<String, Object> workflow) {
        if (workflow == null || workflow.isEmpty()) {
            return Map.of("success", false, "message", "工作流不能为空");
        }
        workflowStore().write(new String[]{CUSTOM_WORKFLOW_FILE}, workflow);
        return Map.of("success", true, "message", "工作流已保存，下次生成生效");
    }

    /** 恢复内置模板（删除自定义工作流）。 */
    public Map<String, Object> resetCustomWorkflow() {
        workflowStore().delete(CUSTOM_WORKFLOW_FILE);
        return Map.of("success", true, "message", "已恢复内置模板");
    }

    private JsonFileStore workflowStore() {
        JsonFileStore store = workflowStore;
        if (store == null) {
            synchronized (this) {
                store = workflowStore;
                if (store == null) {
                    store = new JsonFileStore(Path.of(dataDir).resolve("image"));
                    workflowStore = store;
                }
            }
        }
        return store;
    }

    /** 把 {{placeholder}} 替换进自定义工作流；数字占位符直接替换为数值类型。 */
    private static Map<String, Object> substitutePlaceholders(Map<String, Object> workflow,
                                                              String prompt, String negativePrompt,
                                                              String model, int width, int height,
                                                              int steps, double cfg, int seed) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("prompt", prompt);
        vars.put("negative_prompt", negativePrompt);
        vars.put("model", model);
        vars.put("width", width);
        vars.put("height", height);
        vars.put("steps", steps);
        vars.put("cfg", cfg);
        vars.put("seed", seed);
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : workflow.entrySet()) {
            copy.put(entry.getKey(), substituteValue(entry.getValue(), vars));
        }
        return copy;
    }

    private static Object substituteValue(Object value, Map<String, Object> vars) {
        if (value instanceof String s) {
            // 整值/数值占位符直接替换为对应类型，避免 ComfyUI 收到字符串数字校验失败
            Object direct = vars.get(s.startsWith("{{") && s.endsWith("}}")
                    ? s.substring(2, s.length() - 2) : null);
            if (direct instanceof Number) {
                return direct;
            }
            String replaced = s;
            for (Map.Entry<String, Object> var : vars.entrySet()) {
                replaced = replaced.replace("{{" + var.getKey() + "}}",
                        String.valueOf(var.getValue()));
            }
            return replaced;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), substituteValue(entry.getValue(), vars));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(substituteValue(item, vars));
            }
            return copy;
        }
        return value;
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

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
    private static final int MAX_INIT_IMAGE_BYTES = 14_000_000;

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
    /** 实时进度（ComfyUI /ws 事件写入；HTTP 轮询读取）。 */
    private volatile String activePromptId;
    private volatile ComfyUiClient.ProgressState currentProgress;
    private volatile long progressStartedAt;
    /** 生成中预览图（ComfyUI /ws 二进制帧 → base64；进度端点返回，下次生成前清空）。 */
    private volatile String previewBase64;

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

    /** 生成入口（兼容旧签名）。 */
    public Map<String, Object> generate(String prompt, String negativePrompt, int width, int height,
                                        int steps, double cfg, int seed,
                                        String modelOverride, String sampler,
                                        List<ComfyUiClient.Lora> loras) {
        return generate(prompt, negativePrompt, width, height, steps, cfg, seed,
                modelOverride, sampler, loras, null, 0.0);
    }

    /**
     * 生成入口：模型自动匹配模板（SD15/SDXL/FLUX），支持 LoRA 注入与自定义工作流。
     * modelOverride 为空时用当前默认模型；custom 工作流存在时按 {{placeholder}} 替换后提交。
     * initImageBase64 非空时走 img2img（上传底图 + LoadImage/VAEEncode + denoise）。
     */
    public Map<String, Object> generate(String prompt, String negativePrompt, int width, int height,
                                        int steps, double cfg, int seed,
                                        String modelOverride, String sampler,
                                        List<ComfyUiClient.Lora> loras,
                                        String initImageBase64, double denoisingStrength) {
        return generate(prompt, negativePrompt, width, height, steps, cfg, seed,
                modelOverride, sampler, loras, initImageBase64, denoisingStrength,
                null, 1.0, null, null);
    }

    /**
     * 生成入口（ControlNet / 局部重绘 inpainting 预设）：
     * controlNetName + controlImageBase64 非空时对 SD1.5/SDXL 模板注入 ControlNetApply 链；
     * maskImageBase64 非空时走 LoadImageMask + SetLatentNoiseMask 局部重绘。
     * 生成过程中 ComfyUI /ws 的预览帧实时写入 previewBase64，进度端点随快照返回。
     */
    public Map<String, Object> generate(String prompt, String negativePrompt, int width, int height,
                                        int steps, double cfg, int seed,
                                        String modelOverride, String sampler,
                                        List<ComfyUiClient.Lora> loras,
                                        String initImageBase64, double denoisingStrength,
                                        String controlNetName, double controlNetStrength,
                                        String controlImageBase64, String maskImageBase64) {
        try {
            String effectiveModel = (modelOverride == null || modelOverride.isBlank())
                    ? (model == null || model.isBlank() ? "model.safetensors" : model)
                    : modelOverride;
            String workflowKind = "sd15";
            boolean img2img = initImageBase64 != null && !initImageBase64.isBlank();
            boolean controlNet = controlNetName != null && !controlNetName.isBlank()
                    && controlImageBase64 != null && !controlImageBase64.isBlank();
            boolean inpainting = maskImageBase64 != null && !maskImageBase64.isBlank();
            String initImageName = null;
            if (img2img) {
                initImageName = uploadInitImage(initImageBase64);
                if (initImageName == null) {
                    return Map.of("success", false, "message", "底图上传失败或 base64 格式错误");
                }
            }
            if (controlNet || inpainting) {
                ComfyUiClient.ModelKind kind = ComfyUiClient.ModelKind.detect(effectiveModel);
                if (kind != ComfyUiClient.ModelKind.SD15
                        && kind != ComfyUiClient.ModelKind.SDXL) {
                    return Map.of("success", false,
                            "message", "ControlNet / 局部重绘预设目前仅支持 SD1.5/SDXL 模型模板");
                }
            }
            String controlImageName = null;
            if (controlNet) {
                controlImageName = uploadInitImage(controlImageBase64, "control_");
                if (controlImageName == null) {
                    return Map.of("success", false, "message", "ControlNet 参考图上传失败");
                }
            }
            String maskImageName = null;
            if (inpainting) {
                maskImageName = uploadInitImage(maskImageBase64, "mask_");
                if (maskImageName == null) {
                    return Map.of("success", false, "message", "蒙版图上传失败");
                }
            }
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
                        ClipVae clipVae = resolveClipVae("flux");
                        workflow = ComfyUiClient.fluxTxt2ImgWorkflow(
                                prompt, negativePrompt, effectiveModel, width, height,
                                steps, cfg, seed, sampler, loras, clipVae.clip(),
                                clipVae.vae(), initImageName, denoisingStrength);
                    }
                    case QWEN -> {
                        workflowKind = "qwen";
                        ClipVae clipVae = resolveClipVae("qwen");
                        workflow = ComfyUiClient.qwenTxt2ImgWorkflow(
                                prompt, negativePrompt, effectiveModel, width, height,
                                steps, cfg, seed, sampler, loras, clipVae.clip(),
                                clipVae.vae(), initImageName, denoisingStrength);
                    }
                    case SD35 -> {
                        workflowKind = "sd35";
                        ClipVae clipVae = resolveClipVae("sd35");
                        workflow = ComfyUiClient.sd35Txt2ImgWorkflow(
                                prompt, negativePrompt, effectiveModel, width, height,
                                steps, cfg, seed, sampler, loras, clipVae.clip(),
                                clipVae.clip2(), clipVae.vae(), initImageName, denoisingStrength);
                    }
                    case SDXL -> {
                        workflowKind = "sdxl";
                        workflow = ComfyUiClient.sdControlNetWorkflow(
                                prompt, negativePrompt, effectiveModel, width, height,
                                steps, cfg, seed, sampler, loras, true,
                                initImageName, denoisingStrength,
                                controlNetName, controlNetStrength, controlImageName,
                                maskImageName);
                    }
                    default -> {
                        workflow = ComfyUiClient.sdControlNetWorkflow(
                                prompt, negativePrompt, effectiveModel, width, height,
                                steps, cfg, seed, sampler, loras, false,
                                initImageName, denoisingStrength,
                                controlNetName, controlNetStrength, controlImageName,
                                maskImageName);
                    }
                }
            }
            String promptId = comfyUiClient.submitPrompt(workflow);
            String imageFile;
            activePromptId = promptId;
            currentProgress = new ComfyUiClient.ProgressState(0.0, 0, 0, "queued");
            previewBase64 = null;
            progressStartedAt = System.currentTimeMillis();
            AutoCloseable listener = comfyUiClient.startProgress(promptId,
                    state -> currentProgress = state,
                    bytes -> previewBase64 = java.util.Base64.getEncoder()
                            .encodeToString(bytes));
            try {
                imageFile = pollForImage(promptId);
            } finally {
                try {
                    listener.close();
                } catch (Exception e) {
                    log.debug("ComfyUI 进度监听关闭异常: {}", e.getMessage());
                }
            }
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
            if (img2img) {
                result.put("mode", "img2img");
            }
            if (inpainting) {
                result.put("mode", "inpaint");
            }
            if (controlNet) {
                result.put("mode", "controlnet");
            }
            return result;
        } catch (Exception e) {
            log.error("图片生成失败: {}", e.getMessage());
            return Map.of("success", false, "message", "图片生成失败: " + e.getMessage());
        }
    }

    /** 上传底图（base64 → ComfyUI /upload/image），失败返回 null。 */
    private String uploadInitImage(String base64) {
        return uploadInitImage(base64, "init_");
    }

    private String uploadInitImage(String base64, String prefix) {
        try {
            String raw = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
            byte[] bytes = java.util.Base64.getDecoder().decode(raw.trim());
            if (bytes.length > MAX_INIT_IMAGE_BYTES) {
                log.warn("底图超过 10MB 上限: {} bytes", bytes.length);
                return null;
            }
            String name = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10)
                    + ".png";
            return comfyUiClient.uploadImage(bytes, name);
        } catch (IllegalArgumentException e) {
            log.warn("底图 base64 解码失败: {}", e.getMessage());
            return null;
        }
    }

    /** FLUX/Qwen/SD3.5 的 CLIP/VAE 文件名发现：优先按关键词匹配 object_info，失败回退默认名。 */
    private ClipVae resolveClipVae(String kind) {
        try {
            List<String> clips = comfyUiClient.listClips();
            List<String> vaes = comfyUiClient.listVaes();
            return switch (kind) {
                case "qwen" -> new ClipVae(
                        pick(clips, "qwen", "qwen_2.5_vl_7b_fp8_scaled.safetensors"),
                        null,
                        pick(vaes, "qwen", "qwen_image_vae.safetensors"));
                case "sd35" -> new ClipVae(
                        pick(clips, "clip_g", "clip_g.safetensors"),
                        pick(clips, "t5xxl", "t5xxl_fp8_e4m3fn.safetensors"),
                        pick(vaes, "sd3.5", "sd3.5_medium_vae.safetensors"));
                default -> new ClipVae(
                        pick(clips, "t5xxl", "t5xxl_fp8_e4m3fn.safetensors"),
                        null,
                        pick(vaes, "ae", "ae.safetensors"));
            };
        } catch (Exception e) {
            log.warn("CLIP/VAE 文件发现失败（使用默认名）: {}", e.getMessage());
            return switch (kind) {
                case "qwen" -> new ClipVae("qwen_2.5_vl_7b_fp8_scaled.safetensors", null,
                        "qwen_image_vae.safetensors");
                case "sd35" -> new ClipVae("clip_g.safetensors", "t5xxl_fp8_e4m3fn.safetensors",
                        "sd3.5_medium_vae.safetensors");
                default -> new ClipVae("t5xxl_fp8_e4m3fn.safetensors", null, "ae.safetensors");
            };
        }
    }

    private static String pick(List<String> names, String keyword, String fallback) {
        if (names == null || names.isEmpty()) {
            return fallback;
        }
        String lower = keyword == null ? "" : keyword.toLowerCase();
        for (String name : names) {
            if (name != null && name.toLowerCase().contains(lower)) {
                return name;
            }
        }
        return names.get(0);
    }

    private record ClipVae(String clip, String clip2, String vae) {
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

    /** 可用 ControlNet 模型列表（/object_info 动态发现）。 */
    public Map<String, Object> listControlNets() {
        List<String> controlNets = comfyUiClient.listControlNets();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("provider", provider);
        result.put("controlnets", controlNets);
        result.put("count", controlNets.size());
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
        ComfyUiClient.ProgressState state = currentProgress;
        if (state == null || activePromptId == null) {
            result.put("progress", 0.0);
            result.put("eta", 0.0);
            result.put("status", "idle");
            return result;
        }
        result.put("progress", state.progress());
        result.put("value", state.value());
        result.put("max", state.max());
        result.put("status", state.status());
        if (previewBase64 != null && !previewBase64.isBlank()) {
            result.put("preview_base64", previewBase64);
        }
        double p = state.progress();
        long elapsed = System.currentTimeMillis() - progressStartedAt;
        double eta = p <= 0.01 ? 0.0 : Math.max(0.0, elapsed * (1.0 - p) / p / 1000.0);
        result.put("eta", Math.round(eta));
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

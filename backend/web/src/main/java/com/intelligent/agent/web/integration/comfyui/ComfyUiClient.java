package com.intelligent.agent.web.integration.comfyui;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.core.io.ByteArrayResource;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.nio.ByteBuffer;

/**
 * ComfyUI 集成客户端（Plan 2 / Task 5）：
 * 系统状态 / 提交工作流 / 历史轮询进度（HTTP 轮询降级路径，
 * 与 Python ComfyUIProvider 的降级策略一致）。2026-08-27 扩展：
 * 多 loader 模型发现（checkpoint/unet/diffusion）、CLIP/VAE 文件发现、
 * /upload/image 图生图、Qwen-Image/SD3.5 模板、/ws 实时进度。
 */
@Slf4j
public class ComfyUiClient {

    /** 模型类型（用于自动选择工作流模板）。 */
    public enum ModelKind {
        SD15, SDXL, FLUX, QWEN, SD35;

        /** 按模型文件名关键词探测类型：qwen → QWEN；sd3.5/sd35 → SD35；flux → FLUX；
         *  sdxl/pony/playground → SDXL；其余 → SD15。 */
        public static ModelKind detect(String modelName) {
            if (modelName == null || modelName.isBlank()) {
                return SD15;
            }
            String n = modelName.toLowerCase();
            if (n.contains("qwen")) {
                return QWEN;
            }
            if (n.contains("sd3.5") || n.contains("sd35")) {
                return SD35;
            }
            if (n.contains("flux")) {
                return FLUX;
            }
            if (n.contains("sdxl") || n.contains("pony") || n.contains("playground")) {
                return SDXL;
            }
            return SD15;
        }
    }

    /** LoRA 注入描述（名称 + 模型/CLIP 强度）。 */
    public record Lora(String name, double strengthModel, double strengthClip) {
    }

    /**
     * 解析生成请求里的 loras 参数。支持三种形态：
     * List&lt;String&gt;（"name" 或 "name:strengthModel[:strengthClip]"）、
     * List&lt;Map&gt;（{name, strength_model, strength_clip}）、
     * 逗号分隔字符串。无法识别的条目静默跳过。
     */
    public static List<Lora> parseLoras(Object value) {
        List<Lora> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object name = m.get("name");
                    if (name == null || String.valueOf(name).isBlank()) {
                        continue;
                    }
                    result.add(new Lora(String.valueOf(name).trim(),
                            number(m.get("strength_model"), 1.0),
                            number(m.get("strength_clip"), 1.0)));
                } else if (item != null) {
                    parseLoraString(String.valueOf(item), result);
                }
            }
        } else if (value instanceof String s && !s.isBlank()) {
            for (String part : s.split(",")) {
                parseLoraString(part, result);
            }
        }
        return result;
    }

    private static void parseLoraString(String raw, List<Lora> result) {
        String[] parts = raw.trim().split(":");
        if (parts.length == 0 || parts[0].isBlank()) {
            return;
        }
        double modelStrength = parts.length > 1 ? parseDouble(parts[1], 1.0) : 1.0;
        double clipStrength = parts.length > 2 ? parseDouble(parts[2], modelStrength) : modelStrength;
        result.add(new Lora(parts[0].trim(), modelStrength, clipStrength));
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue()
                : (value instanceof String s ? parseDouble(s, fallback) : fallback);
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    private final String baseUrl;
    private final boolean enabled;
    private final boolean wsProgressEnabled;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final HttpClient wsHttpClient;

    public ComfyUiClient(String baseUrl, boolean enabled, ObjectMapper objectMapper) {
        this(baseUrl, enabled, objectMapper, true);
    }

    /** @param wsProgressEnabled 是否订阅 /ws 实时进度（单测环境 MockWebServer 不响应 WS 握手时置 false）。 */
    public ComfyUiClient(String baseUrl, boolean enabled, ObjectMapper objectMapper,
                         boolean wsProgressEnabled) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.enabled = enabled;
        this.wsProgressEnabled = wsProgressEnabled;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.clientId = "agent-java-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.wsHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Map<String, Object> systemStats() {
        return getJson("system_stats");
    }

    public String submitPrompt(Map<String, Object> workflow) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", workflow);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl + "prompt", new HttpEntity<>(body, headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("ComfyUI 提交失败: HTTP " + response.getStatusCode());
        }
        Map<?, ?> parsed = parse(response.getBody());
        Object promptId = parsed.get("prompt_id");
        return promptId == null ? null : String.valueOf(promptId);
    }

    /** ComfyUI 可用模型列表：解析 CheckpointLoaderSimple(ckpt_name)、UNETLoader(unet_name)、
     *  DiffusionModelLoader(model)，去重保序——FLUX/Qwen/SD3.5 等走 UNET 的模型也能被发现。 */
    public List<String> listModels() {
        try {
            Map<String, Object> objectInfo = getJson("object_info");
            Set<String> models = new LinkedHashSet<>();
            collectLoaderNames(objectInfo, "CheckpointLoaderSimple", "ckpt_name", models);
            collectLoaderNames(objectInfo, "UNETLoader", "unet_name", models);
            collectLoaderNames(objectInfo, "DiffusionModelLoader", "model", models);
            return new ArrayList<>(models);
        } catch (Exception e) {
            log.warn("ComfyUI 模型列表查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** ComfyUI 可用 CLIP/文本编码器文件（/object_info 解析 CLIPLoader.clip_name）。 */
    public List<String> listClips() {
        try {
            Set<String> clips = new LinkedHashSet<>();
            collectLoaderNames(getJson("object_info"), "CLIPLoader", "clip_name", clips);
            return new ArrayList<>(clips);
        } catch (Exception e) {
            log.warn("ComfyUI CLIP 列表查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** ComfyUI 可用 VAE 文件（/object_info 解析 VAELoader.vae_name）。 */
    public List<String> listVaes() {
        try {
            Set<String> vaes = new LinkedHashSet<>();
            collectLoaderNames(getJson("object_info"), "VAELoader", "vae_name", vaes);
            return new ArrayList<>(vaes);
        } catch (Exception e) {
            log.warn("ComfyUI VAE 列表查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static void collectLoaderNames(Map<String, Object> objectInfo, String nodeType,
                                           String field, Set<String> out) {
        Object loader = objectInfo.get(nodeType);
        if (!(loader instanceof Map)) {
            return;
        }
        Object input = ((Map<?, ?>) loader).get("input");
        if (!(input instanceof Map)) {
            return;
        }
        Object required = ((Map<?, ?>) input).get("required");
        if (!(required instanceof Map)) {
            return;
        }
        Object names = ((Map<?, ?>) required).get(field);
        if (names instanceof List && !((List<?>) names).isEmpty()
                && ((List<?>) names).get(0) instanceof List) {
            for (Object name : (List<?>) ((List<?>) names).get(0)) {
                if (name != null && !String.valueOf(name).isBlank()) {
                    out.add(String.valueOf(name));
                }
            }
        }
    }

    /** 上传底图（/upload/image，multipart），返回 ComfyUI 内可用的图片名（input 目录）。 */
    public String uploadImage(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("底图为空");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            org.springframework.util.LinkedMultiValueMap<String, Object> body =
                    new org.springframework.util.LinkedMultiValueMap<>();
            body.add("image", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
            body.add("overwrite", "true");
            body.add("type", "input");
            ResponseEntity<String> response = restTemplate.postForEntity(
                    baseUrl + "upload/image", new HttpEntity<>(body, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("ComfyUI /upload/image 失败: HTTP "
                        + response.getStatusCode());
            }
            Map<?, ?> parsed = parse(response.getBody());
            Object name = parsed.get("name");
            return name == null ? filename : String.valueOf(name);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ComfyUI 底图上传失败: " + e.getMessage(), e);
        }
    }

    /** ComfyUI 可用 LoRA 列表（/object_info 解析 LoraLoader + LoraLoaderModelOnly，去重保序）。 */
    public List<String> listLoras() {
        try {
            Map<String, Object> objectInfo = getJson("object_info");
            Set<String> loras = new LinkedHashSet<>();
            for (String nodeType : List.of("LoraLoader", "LoraLoaderModelOnly")) {
                Object loader = objectInfo.get(nodeType);
                if (loader instanceof Map) {
                    Object input = ((Map<?, ?>) loader).get("input");
                    if (input instanceof Map) {
                        Object required = ((Map<?, ?>) input).get("required");
                        if (required instanceof Map) {
                            Object loraName = ((Map<?, ?>) required).get("lora_name");
                            if (loraName instanceof List && !((List<?>) loraName).isEmpty()
                                    && ((List<?>) loraName).get(0) instanceof List) {
                                for (Object lora : (List<?>) ((List<?>) loraName).get(0)) {
                                    loras.add(String.valueOf(lora));
                                }
                            }
                        }
                    }
                }
            }
            return new ArrayList<>(loras);
        } catch (Exception e) {
            log.warn("ComfyUI LoRA 列表查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 下载生成图片二进制（/view?filename=...）。 */
    public byte[] viewImage(String filename) {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                baseUrl + "view?filename=" + filename, HttpMethod.GET, null, byte[].class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("ComfyUI /view 失败: HTTP " + response.getStatusCode());
        }
        return response.getBody() == null ? new byte[0] : response.getBody();
    }

    /** 内置 txt2img 默认工作流（SD1.5 模板，无 LoRA，euler/normal 采样）。 */
    public static Map<String, Object> defaultTxt2ImgWorkflow(String prompt, String negativePrompt,
                                                             String model, int width, int height,
                                                             int steps, double cfg, int seed) {
        return txt2ImgWorkflow(prompt, negativePrompt, model, width, height, steps, cfg, seed,
                "euler", List.of());
    }

    /** SD1.5 工作流模板（支持采样器 + LoRA 链注入）。 */
    public static Map<String, Object> txt2ImgWorkflow(String prompt, String negativePrompt,
                                                      String model, int width, int height,
                                                      int steps, double cfg, int seed,
                                                      String sampler, List<Lora> loras) {
        return sdWorkflow(prompt, negativePrompt, model, width, height, steps, cfg, seed,
                sampler, loras, false, null, 1.0);
    }

    /** SD1.5 工作流模板（img2img：initImageName 非空时 LoadImage+VAEEncode 代替空 latent）。 */
    public static Map<String, Object> txt2ImgWorkflow(String prompt, String negativePrompt,
                                                      String model, int width, int height,
                                                      int steps, double cfg, int seed,
                                                      String sampler, List<Lora> loras,
                                                      String initImageName, double denoise) {
        return sdWorkflow(prompt, negativePrompt, model, width, height, steps, cfg, seed,
                sampler, loras, false, initImageName, denoise);
    }

    /** SDXL 工作流模板：负向提示走 CLIPSetLastLayer(-2)，采样器默认 karras。 */
    public static Map<String, Object> sdxlTxt2ImgWorkflow(String prompt, String negativePrompt,
                                                          String model, int width, int height,
                                                          int steps, double cfg, int seed,
                                                          String sampler, List<Lora> loras) {
        return sdWorkflow(prompt, negativePrompt, model, width, height, steps, cfg, seed,
                sampler, loras, true, null, 1.0);
    }

    /** SDXL 工作流模板（img2img）。 */
    public static Map<String, Object> sdxlTxt2ImgWorkflow(String prompt, String negativePrompt,
                                                          String model, int width, int height,
                                                          int steps, double cfg, int seed,
                                                          String sampler, List<Lora> loras,
                                                          String initImageName, double denoise) {
        return sdWorkflow(prompt, negativePrompt, model, width, height, steps, cfg, seed,
                sampler, loras, true, initImageName, denoise);
    }

    private static Map<String, Object> sdWorkflow(String prompt, String negativePrompt,
                                                  String model, int width, int height,
                                                  int steps, double cfg, int seed,
                                                  String sampler, List<Lora> loras, boolean sdxl,
                                                  String initImageName, double denoise) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("4", node("CheckpointLoaderSimple", Map.of("ckpt_name", model)));
        String modelRef = "4";
        String clipRef = "4";
        int loraId = 100;
        for (Lora lora : loras == null ? List.<Lora>of() : loras) {
            String id = String.valueOf(loraId++);
            graph.put(id, node("LoraLoader", Map.of(
                    "lora_name", lora.name(),
                    "strength_model", lora.strengthModel(),
                    "strength_clip", lora.strengthClip(),
                    "model", List.of(modelRef, 0),
                    "clip", List.of(clipRef, 1))));
            modelRef = id;
            clipRef = id;
        }
        graph.put("6", node("CLIPTextEncode", Map.of(
                "text", prompt,
                "clip", List.of(clipRef, 1))));
        graph.put("7", node("CLIPTextEncode", Map.of(
                "text", negativePrompt == null || negativePrompt.isBlank()
                        ? "lowres, bad anatomy" : negativePrompt,
                "clip", List.of(clipRef, 1))));
        String negativeRef = "7";
        if (sdxl) {
            graph.put("10", node("CLIPSetLastLayer", Map.of(
                    "clip", List.of("7", 0), "stop_at_clip_layer", -2)));
            negativeRef = "10";
        }
        Map<String, Object> kInputs = new LinkedHashMap<>();
        kInputs.put("seed", seed);
        kInputs.put("steps", steps);
        kInputs.put("cfg", cfg);
        kInputs.put("sampler_name", sampler == null || sampler.isBlank() ? "euler" : sampler);
        kInputs.put("scheduler", sdxl ? "karras" : "normal");
        if (initImageName != null && !initImageName.isBlank()) {
            graph.put("load_init", node("LoadImage", Map.of("image", initImageName)));
            graph.put("vae_encode", node("VAEEncode", Map.of(
                    "pixels", List.of("load_init", 0), "vae", List.of("4", 2))));
            kInputs.put("denoise", denoise <= 0 ? 0.75 : denoise);
            kInputs.put("latent_image", List.of("vae_encode", 0));
        } else {
            graph.put("5", node("EmptyLatentImage", Map.of(
                    "width", width, "height", height, "batch_size", 1)));
            kInputs.put("denoise", 1.0);
            kInputs.put("latent_image", List.of("5", 0));
        }
        kInputs.put("model", List.of(modelRef, 0));
        kInputs.put("positive", List.of("6", 0));
        kInputs.put("negative", List.of(negativeRef, 0));
        graph.put("3", node("KSampler", kInputs));
        graph.put("8", node("VAEDecode", Map.of(
                "samples", List.of("3", 0), "vae", List.of("4", 2))));
        graph.put("9", node("SaveImage", Map.of(
                "filename_prefix", "agent_gen", "images", List.of("8", 0))));
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("prompt", graph);
        workflow.put("client_id", "agent-java");
        return workflow;
    }

    /**
     * FLUX 工作流模板：UNETLoader + CLIPLoader(flux) + EmptySD3LatentImage，
     * KSampler cfg 固定 1.0（FLUX 引导走独立机制）；LoRA 用 LoraLoaderModelOnly 链。
     * 需 ComfyUI 已放置 flux unet / t5xxl / ae 模型文件。
     */
    public static Map<String, Object> fluxTxt2ImgWorkflow(String prompt, String negativePrompt,
                                                          String model, int width, int height,
                                                          int steps, double cfg, int seed,
                                                          String sampler, List<Lora> loras) {
        return fluxTxt2ImgWorkflow(prompt, negativePrompt, model, width, height, steps, cfg, seed,
                sampler, loras, null, null, null, 1.0);
    }

    /** FLUX 工作流模板（CLIP/VAE 文件名可动态发现；支持 img2img）。 */
    public static Map<String, Object> fluxTxt2ImgWorkflow(String prompt, String negativePrompt,
                                                          String model, int width, int height,
                                                          int steps, double cfg, int seed,
                                                          String sampler, List<Lora> loras,
                                                          String clipName, String vaeName,
                                                          String initImageName, double denoise) {
        return unetWorkflow(prompt, negativePrompt, model, width, height, steps, 1.0, seed,
                sampler, loras, "flux",
                clipName == null || clipName.isBlank() ? "t5xxl_fp8_e4m3fn.safetensors" : clipName,
                null,
                vaeName == null || vaeName.isBlank() ? "ae.safetensors" : vaeName,
                initImageName, denoise);
    }

    /**
     * Qwen-Image 工作流模板（Apache-2.0 免费商用，2025-08 起 ComfyUI 原生支持）：
     * UNETLoader + CLIPLoader(type=qwen_image) + VAELoader + EmptySD3LatentImage。
     * Qwen 的 CFG 引导区间约 1~5，超出会过度引导，这里钳制在 [1,5]。
     */
    public static Map<String, Object> qwenTxt2ImgWorkflow(String prompt, String negativePrompt,
                                                          String model, int width, int height,
                                                          int steps, double cfg, int seed,
                                                          String sampler, List<Lora> loras,
                                                          String clipName, String vaeName,
                                                          String initImageName, double denoise) {
        double clampedCfg = Math.max(1.0, Math.min(5.0, cfg <= 0 ? 4.0 : cfg));
        return unetWorkflow(prompt, negativePrompt, model, width, height, steps, clampedCfg, seed,
                sampler, loras, "qwen_image",
                clipName == null || clipName.isBlank()
                        ? "qwen_2.5_vl_7b_fp8_scaled.safetensors" : clipName,
                null,
                vaeName == null || vaeName.isBlank() ? "qwen_image_vae.safetensors" : vaeName,
                initImageName, denoise);
    }

    /**
     * SD3.5 工作流模板：UNETLoader + DualCLIPLoader(type=sd3, clip_g + t5xxl) +
     * VAELoader + EmptySD3LatentImage，cfg 固定 4.5（medium 官方推荐引导）。
     */
    public static Map<String, Object> sd35Txt2ImgWorkflow(String prompt, String negativePrompt,
                                                          String model, int width, int height,
                                                          int steps, double cfg, int seed,
                                                          String sampler, List<Lora> loras,
                                                          String clipName1, String clipName2,
                                                          String vaeName,
                                                          String initImageName, double denoise) {
        return unetWorkflow(prompt, negativePrompt, model, width, height, steps, 4.5, seed,
                sampler, loras, "sd3",
                clipName1 == null || clipName1.isBlank() ? "clip_g.safetensors" : clipName1,
                clipName2 == null || clipName2.isBlank()
                        ? "t5xxl_fp8_e4m3fn.safetensors" : clipName2,
                vaeName == null || vaeName.isBlank() ? "sd3.5_medium_vae.safetensors" : vaeName,
                initImageName, denoise);
    }

    /** 通用 UNET 系模板（FLUX/Qwen/SD3.5 共用）：LoRA(LoraLoaderModelOnly) + img2img。 */
    private static Map<String, Object> unetWorkflow(String prompt, String negativePrompt,
                                                    String model, int width, int height,
                                                    int steps, double cfg, int seed,
                                                    String sampler, List<Lora> loras,
                                                    String clipType, String clipName,
                                                    String clipName2, String vaeName,
                                                    String initImageName, double denoise) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("1", node("UNETLoader", Map.of(
                "unet_name", model, "weight_dtype", "default")));
        if ("sd3".equals(clipType)) {
            graph.put("2", node("DualCLIPLoader", Map.of(
                    "clip_name1", clipName, "clip_name2", clipName2, "type", clipType)));
        } else {
            graph.put("2", node("CLIPLoader", Map.of(
                    "clip_name", clipName, "type", clipType)));
        }
        String modelRef = "1";
        int loraId = 100;
        for (Lora lora : loras == null ? List.<Lora>of() : loras) {
            String id = String.valueOf(loraId++);
            graph.put(id, node("LoraLoaderModelOnly", Map.of(
                    "lora_name", lora.name(),
                    "strength_model", lora.strengthModel(),
                    "model", List.of(modelRef, 0))));
            modelRef = id;
        }
        graph.put("3", node("CLIPTextEncode", Map.of(
                "text", prompt, "clip", List.of("2", 0))));
        graph.put("4", node("CLIPTextEncode", Map.of(
                "text", negativePrompt == null || negativePrompt.isBlank()
                        ? "lowres, bad anatomy" : negativePrompt,
                "clip", List.of("2", 0))));
        Map<String, Object> kInputs = new LinkedHashMap<>();
        kInputs.put("seed", seed);
        kInputs.put("steps", steps);
        kInputs.put("cfg", cfg);
        kInputs.put("sampler_name", sampler == null || sampler.isBlank() ? "euler" : sampler);
        kInputs.put("scheduler", "simple");
        if (initImageName != null && !initImageName.isBlank()) {
            graph.put("load_init", node("LoadImage", Map.of("image", initImageName)));
            graph.put("vae_encode", node("VAEEncode", Map.of(
                    "pixels", List.of("load_init", 0), "vae", List.of("7", 0))));
            kInputs.put("denoise", denoise <= 0 ? 0.75 : denoise);
            kInputs.put("latent_image", List.of("vae_encode", 0));
        } else {
            graph.put("5", node("EmptySD3LatentImage", Map.of(
                    "width", width, "height", height, "batch_size", 1)));
            kInputs.put("denoise", 1.0);
            kInputs.put("latent_image", List.of("5", 0));
        }
        kInputs.put("model", List.of(modelRef, 0));
        kInputs.put("positive", List.of("3", 0));
        kInputs.put("negative", List.of("4", 0));
        graph.put("6", node("KSampler", kInputs));
        graph.put("7", node("VAELoader", Map.of("vae_name", vaeName)));
        graph.put("8", node("VAEDecode", Map.of(
                "samples", List.of("6", 0), "vae", List.of("7", 0))));
        graph.put("9", node("SaveImage", Map.of(
                "filename_prefix", "agent_gen", "images", List.of("8", 0))));
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("prompt", graph);
        workflow.put("client_id", "agent-java");
        return workflow;
    }

    private static Map<String, Object> node(String classType, Map<String, Object> inputs) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("class_type", classType);
        node.put("inputs", inputs);
        return node;
    }

    public Map<String, Object> history(String promptId) {
        return getJson("history/" + promptId);
    }

    /** 轮询进度：完成返回 1.0；运行中返回 0.5 估算；无记录返回 0。 */
    public double progress(String promptId) {
        try {
            Map<String, Object> history = history(promptId);
            Object entry = history.get(promptId);
            if (!(entry instanceof Map)) {
                return 0.0;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> statusMap = (Map<String, Object>) ((Map<String, Object>) entry)
                    .get("status");
            if (statusMap == null) {
                return 0.0;
            }
            Object completed = statusMap.get("completed");
            Object statusStr = statusMap.get("status_str");
            if (Boolean.TRUE.equals(completed) || "success".equals(statusStr)
                    || "error".equals(statusStr)) {
                return 1.0;
            }
            return 0.5;
        } catch (Exception e) {
            log.warn("ComfyUI 进度查询失败 promptId={}: {}", promptId, e.getMessage());
            return 0.0;
        }
    }

    /** /ws 实时进度快照。 */
    public record ProgressState(double progress, int value, int max, String status) {
    }

    /** 订阅 ComfyUI /ws 实时进度事件；返回 closeable（失败时静默降级为轮询，不影响生成）。 */
    public AutoCloseable startProgress(String promptId, Consumer<ProgressState> listener) {
        if (!enabled || !wsProgressEnabled || promptId == null || listener == null) {
            return () -> { };
        }
        try {
            String wsUrl = baseUrl.replaceFirst("^http", "ws") + "ws?clientId=" + clientId;
            AtomicReference<WebSocket> wsRef = new AtomicReference<>();
            CompletableFuture<WebSocket> future = wsHttpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), new ProgressWebSocket(promptId, listener));
            future.thenAccept(wsRef::set);
            future.exceptionally(ex -> {
                log.debug("ComfyUI /ws 连接失败（降级轮询）: {}", ex.getMessage());
                return null;
            });
            return () -> {
                WebSocket ws = wsRef.get();
                if (ws != null) {
                    ws.abort();
                }
            };
        } catch (Exception e) {
            log.debug("ComfyUI /ws 启动失败（降级轮询）: {}", e.getMessage());
            return () -> { };
        }
    }

    /** ComfyUI /ws 事件监听：progress 数值 + executing(node=null)/execution_* 完成信号。 */
    private final class ProgressWebSocket implements WebSocket.Listener {

        private final String promptId;
        private final Consumer<ProgressState> listener;
        private final StringBuilder textBuffer = new StringBuilder();

        private ProgressWebSocket(String promptId, Consumer<ProgressState> listener) {
            this.promptId = promptId;
            this.listener = listener;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                handleEvent(textBuffer.toString());
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1); // 预览图暂不处理，只续读
            return null;
        }

        private void handleEvent(String json) {
            try {
                Map<?, ?> frame = objectMapper.readValue(json, Map.class);
                Object typeObj = frame.get("type");
                String type = String.valueOf(typeObj);
                Object data = frame.get("data");
                if (!(data instanceof Map)) {
                    return;
                }
                Map<?, ?> d = (Map<?, ?>) data;
                if ("progress".equals(type) && promptId.equals(String.valueOf(d.get("prompt_id")))) {
                    Object valueObj = d.get("value");
                    Object maxObj = d.get("max");
                    int value = valueObj instanceof Number n ? n.intValue() : 0;
                    int max = maxObj instanceof Number m ? m.intValue() : 0;
                    double p = max <= 0 ? 0.0 : Math.min(1.0, (double) value / max);
                    listener.accept(new ProgressState(p, value, max, "running"));
                } else if ("executing".equals(type)
                        && promptId.equals(String.valueOf(d.get("prompt_id")))) {
                    if (d.get("node") == null) {
                        listener.accept(new ProgressState(1.0, 0, 0, "done"));
                    }
                } else if (("execution_success".equals(type) || "execution_error".equals(type))
                        && promptId.equals(String.valueOf(d.get("prompt_id")))) {
                    listener.accept(new ProgressState(1.0, 0, 0,
                            "execution_success".equals(type) ? "done" : "error"));
                }
            } catch (Exception e) {
                log.debug("ComfyUI /ws 事件解析失败: {}", e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl + path, String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("ComfyUI GET " + path + " 失败: HTTP "
                    + response.getStatusCode());
        }
        return (Map<String, Object>) parse(response.getBody());
    }

    private Map<?, ?> parse(String body) {
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("ComfyUI 响应解析失败", e);
        }
    }
}

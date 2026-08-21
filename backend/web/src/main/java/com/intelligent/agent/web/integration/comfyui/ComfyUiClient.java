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

/**
 * ComfyUI 集成客户端（Plan 2 / Task 5）：
 * 系统状态 / 提交工作流 / 历史轮询进度（HTTP 轮询降级路径，
 * 与 Python ComfyUIProvider 的降级策略一致）。
 */
@Slf4j
public class ComfyUiClient {

    /** 模型类型（用于自动选择工作流模板）。 */
    public enum ModelKind {
        SD15, SDXL, FLUX;

        /** 按模型文件名关键词探测类型：flux → FLUX；sdxl/pony/playground → SDXL；其余 → SD15。 */
        public static ModelKind detect(String modelName) {
            if (modelName == null || modelName.isBlank()) {
                return SD15;
            }
            String n = modelName.toLowerCase();
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
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ComfyUiClient(String baseUrl, boolean enabled, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.enabled = enabled;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
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

    /** ComfyUI 可用 checkpoint 模型列表（/object_info 解析 CheckpointLoaderSimple）。 */
    public List<String> listModels() {
        try {
            Map<String, Object> objectInfo = getJson("object_info");
            Object loader = objectInfo.get("CheckpointLoaderSimple");
            if (!(loader instanceof Map)) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> input = (Map<String, Object>) ((Map<String, Object>) loader)
                    .get("input");
            if (input == null) {
                return List.of();
            }
            Object required = input.get("required");
            if (!(required instanceof Map)) {
                return List.of();
            }
            Object ckpt = ((Map<?, ?>) required).get("ckpt_name");
            if (ckpt instanceof List && !((List<?>) ckpt).isEmpty()
                    && ((List<?>) ckpt).get(0) instanceof List) {
                List<String> models = new ArrayList<>();
                for (Object model : (List<?>) ((List<?>) ckpt).get(0)) {
                    models.add(String.valueOf(model));
                }
                return models;
            }
            return List.of();
        } catch (Exception e) {
            log.warn("ComfyUI 模型列表查询失败: {}", e.getMessage());
            return List.of();
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
                sampler, loras, false);
    }

    /** SDXL 工作流模板：负向提示走 CLIPSetLastLayer(-2)，采样器默认 karras。 */
    public static Map<String, Object> sdxlTxt2ImgWorkflow(String prompt, String negativePrompt,
                                                          String model, int width, int height,
                                                          int steps, double cfg, int seed,
                                                          String sampler, List<Lora> loras) {
        return sdWorkflow(prompt, negativePrompt, model, width, height, steps, cfg, seed,
                sampler, loras, true);
    }

    private static Map<String, Object> sdWorkflow(String prompt, String negativePrompt,
                                                  String model, int width, int height,
                                                  int steps, double cfg, int seed,
                                                  String sampler, List<Lora> loras, boolean sdxl) {
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
        graph.put("3", node("KSampler", Map.of(
                "seed", seed, "steps", steps, "cfg", cfg,
                "sampler_name", sampler == null || sampler.isBlank() ? "euler" : sampler,
                "scheduler", sdxl ? "karras" : "normal", "denoise", 1.0,
                "model", List.of(modelRef, 0), "positive", List.of("6", 0),
                "negative", List.of(negativeRef, 0), "latent_image", List.of("5", 0))));
        graph.put("5", node("EmptyLatentImage", Map.of(
                "width", width, "height", height, "batch_size", 1)));
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
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("1", node("UNETLoader", Map.of(
                "unet_name", model, "weight_dtype", "default")));
        graph.put("2", node("CLIPLoader", Map.of(
                "clip_name", "t5xxl_fp8_e4m3fn.safetensors", "type", "flux")));
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
        graph.put("5", node("EmptySD3LatentImage", Map.of(
                "width", width, "height", height, "batch_size", 1)));
        graph.put("6", node("KSampler", Map.of(
                "seed", seed, "steps", steps, "cfg", 1.0,
                "sampler_name", sampler == null || sampler.isBlank() ? "euler" : sampler,
                "scheduler", "simple", "denoise", 1.0,
                "model", List.of(modelRef, 0),
                "positive", List.of("3", 0), "negative", List.of("4", 0),
                "latent_image", List.of("5", 0))));
        graph.put("7", node("VAELoader", Map.of("vae_name", "ae.safetensors")));
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

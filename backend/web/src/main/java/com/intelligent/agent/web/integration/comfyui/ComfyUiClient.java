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

/**
 * ComfyUI 集成客户端（Plan 2 / Task 5）：
 * 系统状态 / 提交工作流 / 历史轮询进度（HTTP 轮询降级路径，
 * 与 Python ComfyUIProvider 的降级策略一致）。
 */
@Slf4j
public class ComfyUiClient {

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

    /** 下载生成图片二进制（/view?filename=...）。 */
    public byte[] viewImage(String filename) {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                baseUrl + "view?filename=" + filename, HttpMethod.GET, null, byte[].class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("ComfyUI /view 失败: HTTP " + response.getStatusCode());
        }
        return response.getBody() == null ? new byte[0] : response.getBody();
    }

    /** 内置 txt2img 默认工作流（与 Python ComfyUIProvider 默认工作流对齐）。 */
    public static Map<String, Object> defaultTxt2ImgWorkflow(String prompt, String negativePrompt,
                                                             String model, int width, int height,
                                                             int steps, double cfg, int seed) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("4", node("CheckpointLoaderSimple", Map.of("ckpt_name", model)));
        graph.put("6", node("CLIPTextEncode", Map.of(
                "text", prompt,
                "clip", List.of("4", 1))));
        graph.put("7", node("CLIPTextEncode", Map.of(
                "text", negativePrompt == null || negativePrompt.isBlank()
                        ? "lowres, bad anatomy" : negativePrompt,
                "clip", List.of("4", 1))));
        graph.put("3", node("KSampler", Map.of(
                "seed", seed, "steps", steps, "cfg", cfg, "sampler_name", "euler",
                "scheduler", "normal", "denoise", 1.0,
                "model", List.of("4", 0), "positive", List.of("6", 0),
                "negative", List.of("7", 0), "latent_image", List.of("5", 0))));
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

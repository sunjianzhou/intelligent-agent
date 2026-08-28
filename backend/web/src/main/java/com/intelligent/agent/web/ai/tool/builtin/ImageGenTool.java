package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.integration.comfyui.ComfyUiClient;
import com.intelligent.agent.web.service.ImageService;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 图片生成 AgentTool（2026-08-15 补齐，对齐 Python ImageGenerationTool）：
 * 委托 {@link ImageService}（ComfyUI 默认 txt2img 工作流），恢复聊天内
 * "帮我画一张..."能力。生成耗时可能较长，超时放宽到 600s。
 */
public class ImageGenTool implements AgentTool {

    private final ImageService imageService;

    public ImageGenTool(ImageService imageService) {
        this.imageService = imageService;
    }

    @Override
    public ToolDefinition definition() {
        java.util.Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("prompt", Map.of("type", "string", "description", "画面描述"));
        properties.put("negative_prompt", Map.of("type", "string", "description", "负面提示"));
        properties.put("size", Map.of("type", "string", "description", "尺寸，如 1024x1024"));
        properties.put("steps", Map.of("type", "integer", "description", "采样步数，默认 20"));
        properties.put("cfg", Map.of("type", "number", "description", "CFG，默认 7.0"));
        properties.put("seed", Map.of("type", "integer", "description", "随机种子"));
        properties.put("sampler", Map.of("type", "string", "description", "采样器，默认 euler"));
        properties.put("loras", Map.of("type", "array", "description", "LoRA 列表"));
        properties.put("model", Map.of("type", "string", "description", "模型名"));
        properties.put("init_image_base64",
                Map.of("type", "string", "description", "图生图底图 base64"));
        properties.put("denoising_strength",
                Map.of("type", "number", "description", "去噪强度，默认 0.75"));
        properties.put("controlnet",
                Map.of("type", "string", "description", "ControlNet 模型名"));
        properties.put("controlnet_strength",
                Map.of("type", "number", "description", "ControlNet 强度，默认 1.0"));
        properties.put("control_image_base64",
                Map.of("type", "string", "description", "ControlNet 参考图 base64"));
        properties.put("mask_image_base64",
                Map.of("type", "string", "description", "局部重绘蒙版 base64"));
        return new ToolDefinition(
                "image_generation", "生成图片（ComfyUI）。用户要求画图/生成图片/设计图时使用。"
                        + "参数: prompt(画面描述,必填), negative_prompt(负面提示,可选),"
                        + " size(尺寸,可选,如 1024x1024 / 512x512 / 768x1024,默认1024x1024),"
                        + " steps(步数,可选,默认20), cfg(可选,默认7.0), seed(可选,随机),"
                        + " sampler(采样器,可选,如 euler/dpmpp_2m,默认 euler),"
                        + " loras(LoRA 列表,可选,如 [\"detail.safetensors:0.8\"]),"
                        + " model(模型名,可选,默认用当前模型),"
                        + " init_image_base64(图生图底图,可选,base64 无前缀),"
                        + " denoising_strength(图生图去噪强度,可选,默认0.75),"
                        + " controlnet(ControlNet 模型名,可选,SD1.5/SDXL),"
                        + " controlnet_strength(ControlNet 强度,可选,默认1.0),"
                        + " control_image_base64(ControlNet 参考图,可选),"
                        + " mask_image_base64(局部重绘蒙版,可选,与 init_image_base64 配合)。",
                false, null, Duration.ofSeconds(600),
                Map.of(
                        "type", "object",
                        "properties", properties,
                        "required", List.of("prompt")));
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        String prompt = str(arguments.get("prompt"));
        if (prompt.isBlank()) {
            return "生成失败: prompt 不能为空";
        }
        String negative = str(arguments.get("negative_prompt"));
        int[] size = parseSize(str(arguments.get("size")));
        int steps = intOr(arguments.get("steps"), 20);
        double cfg = dblOr(arguments.get("cfg"), 7.0);
        int seed = intOr(arguments.get("seed"), (int) (System.nanoTime() % Integer.MAX_VALUE));
        String sampler = str(arguments.get("sampler"));
        List<ComfyUiClient.Lora> loras = ComfyUiClient.parseLoras(arguments.get("loras"));
        String model = str(arguments.get("model"));
        String initImage = str(arguments.get("init_image_base64"));
        double denoise = dblOr(arguments.get("denoising_strength"), 0.75);
        String controlNet = str(arguments.get("controlnet"));
        double controlNetStrength = dblOr(arguments.get("controlnet_strength"), 1.0);
        String controlImage = str(arguments.get("control_image_base64"));
        String maskImage = str(arguments.get("mask_image_base64"));

        Map<String, Object> result = imageService.generate(
                prompt, negative, size[0], size[1], steps, cfg, seed,
                model.isBlank() ? null : model,
                sampler.isBlank() ? null : sampler,
                loras.isEmpty() ? null : loras,
                initImage.isBlank() ? null : initImage,
                denoise,
                controlNet.isBlank() ? null : controlNet,
                controlNetStrength,
                controlImage.isBlank() ? null : controlImage,
                maskImage.isBlank() ? null : maskImage);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return "图片已生成: " + result.get("image_url")
                    + "（prompt_id=" + result.getOrDefault("prompt_id", "") + "）";
        }
        return "生成失败: " + result.getOrDefault("message", "未知错误");
    }

    private static int[] parseSize(String size) {
        if (size != null && !size.isBlank()) {
            String[] parts = size.toLowerCase().split("x");
            if (parts.length == 2) {
                try {
                    return new int[]{
                            Math.max(64, Integer.parseInt(parts[0].trim())),
                            Math.max(64, Integer.parseInt(parts[1].trim()))};
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        return new int[]{1024, 1024};
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intOr(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private static double dblOr(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }
}

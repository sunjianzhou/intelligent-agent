package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
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
        return new ToolDefinition(
                "image_generation", "生成图片（ComfyUI）。用户要求画图/生成图片/设计图时使用。"
                        + "参数: prompt(画面描述,必填), negative_prompt(负面提示,可选),"
                        + " size(尺寸,可选,如 1024x1024 / 512x512 / 768x1024,默认1024x1024),"
                        + " steps(步数,可选,默认20), cfg(可选,默认7.0), seed(可选,随机)。",
                false, null, Duration.ofSeconds(600),
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "prompt", Map.of("type", "string", "description", "画面描述"),
                                "negative_prompt", Map.of("type", "string", "description", "负面提示"),
                                "size", Map.of("type", "string", "description", "尺寸，如 1024x1024"),
                                "steps", Map.of("type", "integer", "description", "采样步数，默认 20"),
                                "cfg", Map.of("type", "number", "description", "CFG，默认 7.0"),
                                "seed", Map.of("type", "integer", "description", "随机种子")),
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

        Map<String, Object> result = imageService.generate(
                prompt, negative, size[0], size[1], steps, cfg, seed);
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

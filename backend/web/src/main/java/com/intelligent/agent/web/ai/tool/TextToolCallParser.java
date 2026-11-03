package com.intelligent.agent.web.ai.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 遗留文本工具调用解析器，支持以下几种格式（与 Python tool_dispatcher 对齐）：
 * <ol>
 *   <li>JSON：裸 JSON 对象（栈匹配顶层大括号块），含 "tool" 键；</li>
 *   <li>OpenAI 风格 JSON：{@code {"name": "...", "arguments": {...}}} 或
 *       {@code {"function": {"name": "...", "arguments": {...}}}}——qwen2.5 等支持原生
 *       Function Calling 的模型偶尔仍把调用写成正文 JSON（2026-11-03 eval unit-002/003
 *       现场），此前只认 "tool" 键会当成普通回答返回；</li>
 *   <li>tag：{@code <tool_call>{...}</tool_call>} 与
 *       {@code <tool_call {...}>} 属性形式；</li>
 *   <li>fenced JSON：markdown 代码围栏（```json ... ```）内的工具 JSON；</li>
 *   <li>plain text：gemma 风格 {@code <|tool_call>call:name{key:value,...}}，
 *       含旧名别名映射。</li>
 * </ol>
 *
 * <p>name/arguments 形态必须能解析到已注册工具（{@link #TextToolCallParser(Function)}），
 * 避免把回答里引用的普通 JSON 示例（{@code {"name": "foo"}}）当成工具调用去执行。</p>
 */
public class TextToolCallParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具名解析器：模型写的名字 → 注册表规范名；未注册返回 null。 */
    private final Function<String, String> toolNameResolver;

    private static final Pattern TAG_BODY =
            Pattern.compile("<tool_call>\\s*(.*?)\\s*</tool_call>", Pattern.DOTALL);
    private static final Pattern TAG_ATTR =
            Pattern.compile("<tool_call\\s+(\\{[^>]+\\})\\s*>", Pattern.DOTALL);
    private static final Pattern FENCE =
            Pattern.compile("```[a-zA-Z]*\\s*(.*?)```", Pattern.DOTALL);
    private static final Pattern GEMMA =
            Pattern.compile("<\\|tool_call>call:(\\w+)\\{([^}]*)\\}");

    /** gemma 纯文本格式的旧名 → 真实工具名映射（与 Python 一致）。 */
    private static final Map<String, String> NAME_ALIASES = Map.of(
            "local_file_read", "FileTool",
            "file_read", "FileTool",
            "web_search", "WebSearchTool",
            "calculator", "CalculatorTool",
            "get_time", "TimeTool"
    );

    /** 不校验工具名（仅用于兼容旧调用方 / 单元测试）。 */
    public TextToolCallParser() {
        this(null);
    }

    public TextToolCallParser(Function<String, String> toolNameResolver) {
        this.toolNameResolver = toolNameResolver;
    }

    public List<ToolCall> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        parsePattern(TAG_BODY, text, calls, seen);
        parsePattern(TAG_ATTR, text, calls, seen);
        parseFenced(text, calls, seen);
        parseBareJson(text, calls, seen);
        parseGemma(text, calls, seen);
        return calls;
    }

    private void parsePattern(Pattern pattern, String text,
                              List<ToolCall> calls, Set<String> seen) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            addJsonCall(matcher.group(1).trim(), calls, seen);
        }
    }

    private void parseFenced(String text, List<ToolCall> calls, Set<String> seen) {
        Matcher matcher = FENCE.matcher(text);
        while (matcher.find()) {
            parseBareJson(matcher.group(1), calls, seen);
        }
    }

    private void parseBareJson(String text, List<ToolCall> calls, Set<String> seen) {
        int depth = 0;
        int braceStart = -1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '{') {
                if (depth == 0) {
                    braceStart = i;
                }
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0 && braceStart >= 0) {
                    addJsonCall(text.substring(braceStart, i + 1), calls, seen);
                    braceStart = -1;
                }
            }
        }
    }

    private void parseGemma(String text, List<ToolCall> calls, Set<String> seen) {
        Matcher matcher = GEMMA.matcher(text);
        while (matcher.find()) {
            String rawName = matcher.group(1);
            String name = NAME_ALIASES.getOrDefault(rawName, rawName);
            Map<String, Object> args = parseKeyValues(matcher.group(2));
            ToolCall call = ToolCall.of(name, args);
            if (seen.add(dedupKey(call))) {
                calls.add(call);
            }
        }
    }

    private static Map<String, Object> parseKeyValues(String argsText) {
        Map<String, Object> args = new TreeMap<>();
        if (argsText == null || argsText.isBlank()) {
            return args;
        }
        for (String pair : argsText.split(",")) {
            String trimmed = pair.trim();
            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim()
                        .replaceAll("^[\"']|[\"']$", "");
                if (!key.isEmpty()) {
                    args.put(key, value);
                }
            }
        }
        return args;
    }

    private void addJsonCall(String json, List<ToolCall> calls, Set<String> seen) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isObject()) {
                return;
            }
            ToolCall call = legacyToolKeyCall(node);
            if (call == null) {
                call = openAiStyleCall(node);
            }
            if (call != null && seen.add(dedupKey(call))) {
                calls.add(call);
            }
        } catch (Exception ignored) {
            // 非工具 JSON，忽略
        }
    }

    /** 旧格式 {@code {"tool": "...", "args": {...}}}（工具名不做注册校验，保持历史行为）。 */
    private ToolCall legacyToolKeyCall(JsonNode node) {
        if (!node.has("tool")) {
            return null;
        }
        String name = node.path("tool").asText("");
        if (name.isEmpty()) {
            return null;
        }
        return ToolCall.of(canonicalName(name), argumentsOf(node, "args"));
    }

    /**
     * OpenAI 风格 {@code {"name": ..., "arguments": {...}}} /
     * {@code {"function": {"name": ..., "arguments": {...}}}}。
     * 必须解析到已注册工具（含别名），否则不当作工具调用。
     */
    private ToolCall openAiStyleCall(JsonNode node) {
        JsonNode holder = node;
        if (!node.has("name") && node.has("function") && node.get("function").isObject()) {
            holder = node.get("function");
        }
        String rawName = holder.path("name").asText("").trim();
        if (rawName.isEmpty()) {
            return null;
        }
        Map<String, Object> args = null;
        for (String field : new String[]{"arguments", "parameters", "args"}) {
            JsonNode value = holder.get(field);
            if (value != null && value.isObject()) {
                args = MAPPER.convertValue(value, new TypeReference<Map<String, Object>>() { });
                break;
            }
        }
        // 没有参数对象的 {"name": "..."} 多半是回答里的普通 JSON，不当工具调用
        if (args == null) {
            return null;
        }
        if (toolNameResolver == null) {
            // 没有注册表校验时保守：空参数不解析，避免误执行
            return args.isEmpty() ? null : ToolCall.of(rawName, args);
        }
        String canonical = toolNameResolver.apply(rawName);
        return canonical == null ? null : ToolCall.of(canonical, args);
    }

    private static Map<String, Object> argumentsOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            return Map.of();
        }
        return MAPPER.convertValue(value, new TypeReference<Map<String, Object>>() { });
    }

    private String canonicalName(String name) {
        String canonical = toolNameResolver == null ? null : toolNameResolver.apply(name);
        return canonical == null ? name : canonical;
    }

    private static String dedupKey(ToolCall call) {
        return call.name() + "|" + new TreeMap<>(call.arguments()).toString();
    }
}

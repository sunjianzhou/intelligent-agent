package com.intelligent.agent.web.ai.tool.builtin;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import com.intelligent.agent.web.infrastructure.monitoring.SystemResourceService;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 系统信息工具（2026-08-15 补齐，对齐 Python system_info FunctionTool）：
 * 返回 CPU/内存/磁盘/Ollama 模型等运行状态摘要。
 */
public class SystemInfoTool implements AgentTool {

    private final SystemResourceService systemResourceService;

    public SystemInfoTool(SystemResourceService systemResourceService) {
        this.systemResourceService = systemResourceService;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "system_info", "查询系统运行状态：CPU 使用率、内存、磁盘、Ollama 已加载模型、"
                        + "GPU 信息等。无参数。",
                true, null, Duration.ofSeconds(15),
                Map.of("type", "object", "properties", Map.of(), "required", List.of()));
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        Map<String, Object> info = systemResourceService.get();
        StringBuilder sb = new StringBuilder();
        sb.append("系统状态:\n");
        if (info.get("cpu_percent") != null) {
            sb.append("- CPU: ").append(info.get("cpu_percent")).append("%\n");
        }
        if (info.get("memory_percent") != null) {
            sb.append("- 内存: ").append(info.get("memory_percent")).append("%")
                    .append(" (used=").append(info.get("memory_used_mb")).append("MB, total=")
                    .append(info.get("memory_total_mb")).append("MB)\n");
        }
        Object disks = info.get("disks");
        if (disks instanceof List && !((List<?>) disks).isEmpty()) {
            sb.append("- 磁盘:\n");
            for (Object disk : (List<?>) disks) {
                if (disk instanceof Map) {
                    Map<?, ?> d = (Map<?, ?>) disk;
                    sb.append("  - ").append(d.get("path")).append(": ")
                            .append(d.get("used_percent")).append("%\n");
                }
            }
        }
        Object models = info.get("ollama_models");
        if (models instanceof List && !((List<?>) models).isEmpty()) {
            sb.append("- Ollama 模型: ").append(models).append('\n');
        } else {
            sb.append("- Ollama 模型: 无/未连接\n");
        }
        if (info.get("gpu") instanceof Map) {
            Map<?, ?> gpu = (Map<?, ?>) info.get("gpu");
            sb.append("- GPU: ").append(gpu.get("name")).append(" (util=")
                    .append(gpu.get("util_percent")).append("%)\n");
        }
        return sb.toString().stripTrailing();
    }
}

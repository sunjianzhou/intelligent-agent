package com.intelligent.agent.web.infrastructure.monitoring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 本地系统资源监控（java 模式替代已退役 Python 的 /api/system/resources）：
 * CPU / 系统内存 / 磁盘 / Ollama 已加载模型。GPU 与逐进程内存依赖原生能力，
 * JDK 无法可靠获取，返回 null / 空集合（前端已有对应降级展示）。
 */
public class SystemResourceService {

    private static final Logger log = LoggerFactory.getLogger(SystemResourceService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final String ollamaBaseUrl;
    private volatile Map<String, Object> gpuCache;
    private volatile long gpuCacheAt;

    public SystemResourceService(String ollamaBaseUrl) {
        this.ollamaBaseUrl = ollamaBaseUrl == null ? "http://localhost:11434"
                : ollamaBaseUrl.replaceAll("/+$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    public Map<String, Object> get() {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        Map<String, Object> result = new LinkedHashMap<>();
        double cpuLoad = os.getCpuLoad();
        double cpuPercent = cpuLoad < 0 ? 0 : cpuLoad * 100;
        int cores = os.getAvailableProcessors();
        result.put("cpu_percent", Math.round(cpuPercent));
        result.put("cpu_count", cores);
        result.put("cpu_used_cores", Math.round(cpuPercent / 100.0 * cores));

        long totalMem = os.getTotalMemorySize();
        long freeMem = os.getFreeMemorySize();
        double memPercent = totalMem <= 0 ? 0 : (totalMem - freeMem) * 100.0 / totalMem;
        result.put("memory_total_gb", round2(totalMem / 1_000_000_000.0));
        result.put("memory_used_gb", round2((totalMem - freeMem) / 1_000_000_000.0));
        result.put("memory_percent", Math.round(memPercent));

        result.put("disks", disks());
        result.put("processes", Map.of());
        result.put("top_other_processes", List.of());
        result.put("gpu", gpuInfo());
        result.put("ollama_models", ollamaModels());
        return result;
    }

    /** nvidia-smi 采集 GPU 信息；不可用/失败返回 null（前端展示"未检测到独立 GPU"）。2s 缓存避免高频进程启动。 */
    private Map<String, Object> gpuInfo() {
        long now = System.currentTimeMillis();
        if (gpuCache != null && now - gpuCacheAt < 2000) {
            return gpuCache;
        }
        Map<String, Object> info = null;
        try {
            Process process = new ProcessBuilder(
                    "nvidia-smi",
                    "--query-gpu=name,utilization.gpu,temperature.gpu,memory.used,memory.total,memory.utilization",
                    "--format=csv,noheader,nounits")
                    .redirectErrorStream(true)
                    .start();
            if (process.waitFor(3, TimeUnit.SECONDS)) {
                String output = new String(process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                String line = output.lines().findFirst().orElse("");
                if (!line.isBlank()) {
                    String[] parts = line.split("\\s*,\\s*");
                    if (parts.length >= 6) {
                        info = new LinkedHashMap<>();
                        info.put("name", parts[0]);
                        info.put("util_percent", numDouble(parts[1]));
                        info.put("temperature", numDouble(parts[2]));
                        info.put("mem_used_mb", numDouble(parts[3]));
                        info.put("mem_total_mb", numDouble(parts[4]));
                        info.put("mem_percent", numDouble(parts[5]));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("nvidia-smi 不可用: {}", e.getMessage());
        }
        gpuCache = info;
        gpuCacheAt = now;
        return info;
    }

    private List<Map<String, Object>> disks() {
        List<Map<String, Object>> disks = new ArrayList<>();
        for (File root : File.listRoots()) {
            long total = root.getTotalSpace();
            long free = root.getFreeSpace();
            long used = total - free;
            Map<String, Object> disk = new LinkedHashMap<>();
            disk.put("mountpoint", root.getPath());
            disk.put("total_gb", round2(total / 1_000_000_000.0));
            disk.put("used_gb", round2(used / 1_000_000_000.0));
            disk.put("free_gb", round2(free / 1_000_000_000.0));
            disk.put("percent", total <= 0 ? 0 : Math.round(used * 100.0 / total));
            disks.add(disk);
        }
        return disks;
    }

    private List<Map<String, Object>> ollamaModels() {
        try {
            String url = ollamaBaseUrl + "/api/ps";
            String body = restTemplate.getForObject(url, String.class);
            if (body == null) {
                return List.of();
            }
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {});
            Object models = parsed.get("models");
            if (!(models instanceof List)) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object model : (List<?>) models) {
                if (!(model instanceof Map)) {
                    continue;
                }
                Map<?, ?> m = (Map<?, ?>) model;
                Map<String, Object> item = new LinkedHashMap<>();
                Object name = m.get("name");
                item.put("name", name == null ? "" : String.valueOf(name));
                item.put("size_gb", round2(num(m.get("size")) / 1_000_000_000.0));
                item.put("vram_gb", round2(num(m.get("size_vram")) / 1_000_000_000.0));
                Object expiresAt = m.get("expires_at");
                item.put("expires_at", expiresAt == null ? null : String.valueOf(expiresAt));
                out.add(item);
            }
            return out;
        } catch (Exception e) {
            log.debug("Ollama /api/ps 查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static long num(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static double numDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

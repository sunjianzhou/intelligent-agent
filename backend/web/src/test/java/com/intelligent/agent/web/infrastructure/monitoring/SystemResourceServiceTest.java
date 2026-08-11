package com.intelligent.agent.web.infrastructure.monitoring;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地系统资源监控：字段结构完整性（值随平台变化，仅校验形状与类型）。
 */
class SystemResourceServiceTest {

    @Test
    void returnsExpectedStructure() {
        Map<String, Object> result = new SystemResourceService("http://localhost:11434").get();

        assertThat(result).containsKeys(
                "cpu_percent", "cpu_count", "cpu_used_cores",
                "memory_total_gb", "memory_used_gb", "memory_percent",
                "disks", "processes", "top_other_processes", "gpu", "ollama_models");
        assertThat(result.get("cpu_percent")).isInstanceOf(Number.class);
        assertThat(result.get("disks")).isInstanceOf(List.class);
        Object gpu = result.get("gpu");
        assertThat(gpu == null || gpu instanceof Map).isTrue();
        if (gpu instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> gpuMap = (Map<String, Object>) gpu;
            assertThat(gpuMap).containsKeys(
                    "name", "util_percent", "temperature", "mem_used_mb", "mem_total_mb", "mem_percent");
        }
        assertThat(result.get("ollama_models")).isInstanceOf(List.class);
    }
}

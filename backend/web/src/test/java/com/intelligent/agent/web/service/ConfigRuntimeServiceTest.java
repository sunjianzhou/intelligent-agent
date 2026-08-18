package com.intelligent.agent.web.service;

import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.MemoryRepository;
import com.intelligent.agent.web.ai.memory.SemanticResponseCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** runtime 配置 → LLM 请求参数映射：只注入持久化的值，保存后重启仍生效，边界 clamp。 */
class ConfigRuntimeServiceTest {

    @TempDir
    Path tempDir;

    private ConfigRuntimeService newService() {
        ConfigRuntimeService service = new ConfigRuntimeService(
                mock(MemoryRepository.class),
                mock(ConversationMemoryService.class),
                new SemanticResponseCache());
        ReflectionTestUtils.setField(service, "dataDir", tempDir.toString());
        return service;
    }

    @Test
    void llmRequestOptionsOnlyContainsPersistedValues() {
        ConfigRuntimeService service = newService();
        assertThat(service.llmRequestOptions()).isEmpty();

        service.patch(Map.of(
                "ollama_temperature", 0.3,
                "ollama_max_tokens", 1024,
                "ollama_num_ctx", 8192,
                "chat_timeout", 120));

        assertThat(service.llmRequestOptions())
                .containsEntry("temperature", 0.3)
                .containsEntry("max_tokens", 1024.0)
                .containsEntry("num_ctx", 8192.0)
                .containsEntry("chat_timeout", 120.0)
                .doesNotContainKey("inference_concurrency");
    }

    @Test
    void llmRequestOptionsSurvivesRestart() {
        newService().patch(Map.of("ollama_temperature", 0.5));

        assertThat(newService().llmRequestOptions()).containsEntry("temperature", 0.5);
    }

    @Test
    void patchClampsRuntimeValuesToLimits() {
        newService().patch(Map.of("ollama_temperature", 99.0));

        assertThat(newService().llmRequestOptions()).containsEntry("temperature", 2.0);
    }
}

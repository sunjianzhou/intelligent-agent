package com.intelligent.agent.web.service;

import com.intelligent.agent.web.ai.memory.ConversationMemoryService;
import com.intelligent.agent.web.ai.memory.MemoryRepository;
import com.intelligent.agent.web.ai.memory.SemanticResponseCache;
import com.intelligent.agent.web.ai.agent.ActiveChatLimiter;
import com.intelligent.agent.web.ai.llm.InferenceGate;
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
                new SemanticResponseCache(),
                new InferenceGate(1));
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

    @Test
    void toolResultMaxCharsDefaultsTo5000() {
        assertThat(newService().toolResultMaxChars()).isEqualTo(5000);
    }

    @Test
    void toolResultMaxCharsUsesPersistedValue() {
        newService().patch(Map.of("tool_result_max_chars", 20000));

        assertThat(newService().toolResultMaxChars()).isEqualTo(20000);
    }

    @Test
    void inferenceConcurrencyDefaultsToOne() {
        assertThat(newService().inferenceConcurrency()).isEqualTo(1);
    }

    @Test
    void patchAppliesInferenceConcurrencyToGate() {
        InferenceGate gate = new InferenceGate(1);
        ConfigRuntimeService service = new ConfigRuntimeService(
                mock(MemoryRepository.class),
                mock(ConversationMemoryService.class),
                new SemanticResponseCache(),
                gate);
        ReflectionTestUtils.setField(service, "dataDir", tempDir.toString());

        service.patch(Map.of("inference_concurrency", 5));

        assertThat(gate.maxConcurrency()).isEqualTo(5);
        assertThat(service.inferenceConcurrency()).isEqualTo(5);
        assertThat(service.get().get("usage"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("active_inferences", 0)
                .containsEntry("concurrency_slots", 5.0);
    }

    @Test
    void usageReportsRealActiveInferences() throws Exception {
        InferenceGate gate = new InferenceGate(1);
        ConfigRuntimeService service = new ConfigRuntimeService(
                mock(MemoryRepository.class),
                mock(ConversationMemoryService.class),
                new SemanticResponseCache(),
                gate);
        ReflectionTestUtils.setField(service, "dataDir", tempDir.toString());
        service.patch(Map.of("inference_concurrency", 3));

        gate.acquire();
        gate.acquire();

        assertThat(service.get().get("usage"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("active_inferences", 2)
                .containsEntry("concurrency_slots", 3.0);
    }

    @Test
    void patchAppliesStreamConcurrencyToLimiter() {
        ActiveChatLimiter limiter = new ActiveChatLimiter(32);
        ConfigRuntimeService service = new ConfigRuntimeService(
                mock(MemoryRepository.class),
                mock(ConversationMemoryService.class),
                new SemanticResponseCache(),
                new InferenceGate(1),
                limiter);
        ReflectionTestUtils.setField(service, "dataDir", tempDir.toString());

        service.patch(Map.of("stream_concurrency", 8));

        assertThat(limiter.maxConcurrency()).isEqualTo(8);
        assertThat(service.streamConcurrency()).isEqualTo(8);
        assertThat(service.get().get("usage"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("stream_slots", 8.0)
                .containsEntry("active_streams", 0);
    }
}

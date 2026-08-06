package com.intelligent.agent.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BuildBaselineTest {

    @Value("${ai.runtime.mode}")
    String mode;

    @Test
    void defaultRuntimeMode_isPython() {
        assertThat(mode).isEqualTo("python");
    }
}
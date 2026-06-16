package com.intelligent.agent.web.feishu;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FeishuConfigTest {

    @Autowired
    private FeishuConfig feishuConfig;

    @Autowired
    @Qualifier("feishuStreamExecutor")
    private ExecutorService feishuStreamExecutor;

    @Test
    void defaultEnabled_isFalse() {
        assertThat(feishuConfig.isEnabled()).isFalse();
    }

    @Test
    void feishuStreamExecutor_beanExists() {
        assertThat(feishuStreamExecutor).isNotNull();
    }
}

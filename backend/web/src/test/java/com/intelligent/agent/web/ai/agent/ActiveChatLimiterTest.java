package com.intelligent.agent.web.ai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 流式对话并发上限：满则拒绝、释放后可复用、运行时可调上限。 */
class ActiveChatLimiterTest {

    @Test
    void rejectsWhenAtCapacity() {
        ActiveChatLimiter limiter = new ActiveChatLimiter(1);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        assertThat(limiter.active()).isEqualTo(1);
    }

    @Test
    void releaseFreesSlot() {
        ActiveChatLimiter limiter = new ActiveChatLimiter(1);
        limiter.tryAcquire();
        limiter.release();

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.active()).isEqualTo(1);
    }

    @Test
    void releaseWithoutAcquireDoesNotGoNegative() {
        ActiveChatLimiter limiter = new ActiveChatLimiter(2);

        limiter.release();
        limiter.release();

        assertThat(limiter.active()).isZero();
    }

    @Test
    void adjustsMaxAtRuntime() {
        ActiveChatLimiter limiter = new ActiveChatLimiter(1);
        limiter.tryAcquire();

        limiter.setMaxConcurrency(2);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.maxConcurrency()).isEqualTo(2);
    }
}

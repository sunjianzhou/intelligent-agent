package com.intelligent.agent.web.domain.conversation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话并发写契约（2026-08-15 补充）：多线程同时 append 同一会话，
 * synchronized 串行化读-改-写，不允许丢消息。
 */
class ConversationServiceConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentAppendsToSameSessionLoseNoMessages() throws Exception {
        ConversationService service = new ConversationService(tempDir);
        String sessionId = "concurrent-session";
        int threads = 8;
        int perThread = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                final int threadNo = t;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        Map<String, Object> msg = Map.of(
                                "role", "user",
                                "content", "thread-" + threadNo + "-msg-" + i);
                        service.append("alice", sessionId, List.of(msg));
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        Map<String, Object> session = service.getConversation("alice", sessionId);
        @SuppressWarnings("unchecked")
        Map<String, Object> sessionData = (Map<String, Object>) session.get("session");
        @SuppressWarnings("unchecked")
        List<Object> messages = (List<Object>) sessionData.get("messages");
        assertThat(messages).hasSize(threads * perThread);
    }

    @Test
    void concurrentAppendAndRetractRemainConsistent() throws Exception {
        ConversationService service = new ConversationService(tempDir);
        String sessionId = "race-session";
        service.append("alice", sessionId, List.of(
                Map.of("id", "m1", "role", "user", "content", "第一条")));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                for (int i = 0; i < 20; i++) {
                    service.append("alice", sessionId,
                            List.of(Map.of("role", "user", "content", "追加-" + i)));
                }
                return null;
            });
            pool.submit(() -> {
                service.retract("alice", sessionId, List.of("m1"));
                return null;
            });
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        Map<String, Object> session = service.getConversation("alice", sessionId);
        @SuppressWarnings("unchecked")
        Map<String, Object> sessionData = (Map<String, Object>) session.get("session");
        @SuppressWarnings("unchecked")
        List<Object> messages = (List<Object>) sessionData.get("messages");
        // 20 条追加应全部保留；m1 被撤回
        assertThat(messages).hasSize(20);
        assertThat(messages.stream()
                .map(m -> ((Map<?, ?>) m).get("id"))
                .noneMatch("m1"::equals)).isTrue();
    }
}

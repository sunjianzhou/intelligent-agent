package com.intelligent.agent.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 描述：
 *
 * @author lin miao
 * @date 2026/5/5
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "streamExecutor", destroyMethod = "shutdown")
    public ExecutorService streamExecutor() {
        return new ThreadPoolExecutor(
                2,                               // corePoolSize：常驻线程数
                10,                              // maximumPoolSize：最大线程数
                60L, TimeUnit.SECONDS,           // 空闲线程存活时间
                new LinkedBlockingQueue<>(100),  // 任务队列容量
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "stream-worker-" + count.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                },
                // AbortPolicy：队列满时抛 RejectedExecutionException，由 WebSocketController
                // 捕获后向客户端返回 503，避免 CallerRunsPolicy 占用 Tomcat 线程影响其他请求
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 非流式 REST /api/chat 的专用执行器：长任务（LLM 推理）不再占用 Tomcat worker 线程，
     * 队列满时抛 RejectedExecutionException，由 ChatController 转成 503 快速失败。
     */
    @Bean(name = "chatExecutor", destroyMethod = "shutdown")
    public ExecutorService chatExecutor() {
        return new ThreadPoolExecutor(
                8,                               // corePoolSize
                32,                              // maximumPoolSize
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "chat-worker-" + count.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 记忆蒸馏/摘要/项目上下文提取的后台执行器：每 5/10/8 轮的 LLM 提取不再阻塞
     * 聊天响应收尾路径；有界队列满时丢弃并告警（后台任务不允许反压到请求线程）。
     */
    @Bean(name = "memoryExecutor", destroyMethod = "shutdown")
    public ExecutorService memoryExecutor() {
        return new ThreadPoolExecutor(
                2,                               // corePoolSize
                4,                               // maximumPoolSize
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "memory-worker-" + count.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}

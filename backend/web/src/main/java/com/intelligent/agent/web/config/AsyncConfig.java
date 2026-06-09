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
}

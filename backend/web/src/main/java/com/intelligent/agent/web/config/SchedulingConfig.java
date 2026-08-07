package com.intelligent.agent.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 调度线程池配置：daemon 线程，避免测试 JVM 因调度线程不退出而挂起，
 * 同时生产环境应用退出时调度线程随之终止。
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadFactory(r -> {
            Thread t = new Thread(r, "agent-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}

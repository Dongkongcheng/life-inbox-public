package com.lifeinbox.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 自动 Analyze、Action Extraction、Vector 与 Relation 派生任务复用同一个有界线程池，
 * 避免再建队列或占用 Web 请求线程。
 */
@Configuration
public class AiBackgroundConfiguration {

    public static final String AI_TASK_EXECUTOR = "aiTaskExecutor";

    @Bean(name = AI_TASK_EXECUTOR)
    public ThreadPoolTaskExecutor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("life-inbox-ai-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}

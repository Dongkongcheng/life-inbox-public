package com.lifeinbox.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AiBackgroundConfigurationTests {

    @Test
    void executorIsSmallBoundedAndRejectsOverflow() {
        ThreadPoolTaskExecutor executor = new AiBackgroundConfiguration().aiTaskExecutor();
        executor.initialize();
        try {
            assertEquals(1, executor.getCorePoolSize());
            assertEquals(2, executor.getMaxPoolSize());
            assertEquals(20, executor.getThreadPoolExecutor().getQueue().remainingCapacity());
            assertInstanceOf(
                    ThreadPoolExecutor.AbortPolicy.class,
                    executor.getThreadPoolExecutor().getRejectedExecutionHandler()
            );
        } finally {
            executor.shutdown();
        }
    }
}

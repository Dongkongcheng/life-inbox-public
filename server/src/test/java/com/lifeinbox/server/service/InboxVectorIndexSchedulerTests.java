package com.lifeinbox.server.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InboxVectorIndexSchedulerTests {

    @Test
    void reusesBoundedExecutorForIndexAndDelete() {
        InboxVectorIndexWorker worker = mock(InboxVectorIndexWorker.class);
        TaskExecutor directExecutor = Runnable::run;
        InboxVectorIndexScheduler scheduler = new InboxVectorIndexScheduler(
                directExecutor,
                worker
        );

        scheduler.scheduleIndex(1L, "attempt-a");
        scheduler.scheduleDelete(1L);

        verify(worker).index(1L, "attempt-a");
        verify(worker).delete(1L);
    }

    @Test
    void rejectedVectorTaskCannotEscapeToBusinessOperation() {
        InboxVectorIndexWorker worker = mock(InboxVectorIndexWorker.class);
        TaskExecutor rejectedExecutor = task -> {
            throw new RejectedExecutionException("queue full");
        };
        InboxVectorIndexScheduler scheduler = new InboxVectorIndexScheduler(
                rejectedExecutor,
                worker
        );

        assertDoesNotThrow(() -> scheduler.scheduleIndex(1L, "attempt-a"));
        assertDoesNotThrow(() -> scheduler.scheduleDelete(1L));
    }
}

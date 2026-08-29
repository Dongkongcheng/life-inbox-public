package com.lifeinbox.server.service;

import com.lifeinbox.server.event.InboxVectorReadyEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class InboxAutoRelationListenerTests {

    private final TaskExecutor executor = mock(TaskExecutor.class);
    private final InboxAutoRelationWorker worker = mock(InboxAutoRelationWorker.class);
    private final RelationProcessingStatusService statusService = mock(
            RelationProcessingStatusService.class
    );

    @Test
    void vectorReadySchedulesBoundedBackgroundWorkWithoutRunningInline() {
        InboxAutoRelationListener listener = new InboxAutoRelationListener(
                executor,
                worker,
                statusService,
                true
        );
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

        listener.onVectorReady(new InboxVectorReadyEvent(10L));

        verify(executor).execute(task.capture());
        verifyNoInteractions(worker);
        task.getValue().run();
        verify(worker).discover(10L);
    }

    @Test
    void disabledAutomationDoesNotScheduleAnything() {
        InboxAutoRelationListener listener = new InboxAutoRelationListener(
                executor,
                worker,
                statusService,
                false
        );

        listener.onVectorReady(new InboxVectorReadyEvent(11L));

        verifyNoInteractions(executor, worker, statusService);
    }

    @Test
    void queueRejectionMarksOnlyNotProcessedAsRetryableFailureAndNeverEscapes() {
        InboxAutoRelationListener listener = new InboxAutoRelationListener(
                executor,
                worker,
                statusService,
                true
        );
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any());

        assertDoesNotThrow(() -> listener.onVectorReady(new InboxVectorReadyEvent(12L)));

        verify(statusService).markAutoSchedulingFailed(
                12L,
                "自动 Relation 队列繁忙，请手动重试"
        );
        verify(worker, never()).discover(12L);
    }
}

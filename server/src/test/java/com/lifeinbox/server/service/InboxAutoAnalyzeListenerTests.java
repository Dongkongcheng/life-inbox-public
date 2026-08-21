package com.lifeinbox.server.service;

import com.lifeinbox.server.event.InboxItemCapturedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class InboxAutoAnalyzeListenerTests {

    private final TaskExecutor taskExecutor = mock(TaskExecutor.class);
    private final InboxAutoAnalyzeWorker worker = mock(InboxAutoAnalyzeWorker.class);
    private final InboxAnalysisStatusService statusService = mock(
            InboxAnalysisStatusService.class
    );

    @Test
    void disabledListenerDoesNotSubmitAutomaticAnalyze() {
        InboxAutoAnalyzeListener listener = listener(false);

        listener.onInboxItemCaptured(new InboxItemCapturedEvent(1L));

        verify(taskExecutor, never()).execute(any());
        verify(worker, never()).analyze(any());
    }

    @Test
    void enabledListenerOnlyQueuesWorkAndDoesNotWaitForAnalyze() {
        InboxAutoAnalyzeListener listener = listener(true);

        listener.onInboxItemCaptured(new InboxItemCapturedEvent(2L));

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(taskCaptor.capture());
        verify(worker, never()).analyze(any());

        taskCaptor.getValue().run();
        verify(worker).analyze(2L);
    }

    @Test
    void rejectedTaskDoesNotFailCommittedCaptureAndMarksRetryableFailure() {
        InboxAutoAnalyzeListener listener = listener(true);
        doThrow(new TaskRejectedException("queue full"))
                .when(taskExecutor).execute(any(Runnable.class));

        assertDoesNotThrow(
                () -> listener.onInboxItemCaptured(new InboxItemCapturedEvent(3L))
        );

        verify(statusService).markAutoSchedulingFailed(
                3L,
                "自动分析队列繁忙，请手动重试"
        );
        verify(worker, never()).analyze(any());
    }

    @Test
    void rejectionStatusWriteFailureIsAlsoContained() {
        InboxAutoAnalyzeListener listener = listener(true);
        doThrow(new TaskRejectedException("queue full"))
                .when(taskExecutor).execute(any(Runnable.class));
        doThrow(new IllegalStateException("database unavailable"))
                .when(statusService).markAutoSchedulingFailed(any(), any());

        assertDoesNotThrow(
                () -> listener.onInboxItemCaptured(new InboxItemCapturedEvent(4L))
        );
    }

    private InboxAutoAnalyzeListener listener(boolean enabled) {
        return new InboxAutoAnalyzeListener(taskExecutor, worker, statusService, enabled);
    }
}

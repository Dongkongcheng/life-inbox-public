package com.lifeinbox.server.service;

import com.lifeinbox.server.event.InboxActionContentReadyEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class InboxAutoActionListenerTests {

    private final TaskExecutor taskExecutor = mock(TaskExecutor.class);
    private final InboxAutoActionWorker worker = mock(InboxAutoActionWorker.class);

    @Test
    void committedContentSchedulesBackgroundOrchestratorWithoutRunningInline() {
        InboxAutoActionListener listener = new InboxAutoActionListener(taskExecutor, worker, true);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

        listener.onContentReady(new InboxActionContentReadyEvent(12L));

        verify(taskExecutor).execute(task.capture());
        verifyNoInteractions(worker);
        task.getValue().run();
        verify(worker).extract(12L);
    }

    @Test
    void disabledAutomationDoesNotScheduleProviderWork() {
        InboxAutoActionListener listener = new InboxAutoActionListener(taskExecutor, worker, false);

        listener.onContentReady(new InboxActionContentReadyEvent(13L));

        verifyNoInteractions(taskExecutor, worker);
    }

    @Test
    void executorRejectionNeverEscapesBackToCommittedCapture() {
        InboxAutoActionListener listener = new InboxAutoActionListener(taskExecutor, worker, true);
        doThrow(new RejectedExecutionException("queue full"))
                .when(taskExecutor).execute(any(Runnable.class));

        assertDoesNotThrow(
                () -> listener.onContentReady(new InboxActionContentReadyEvent(14L))
        );

        verify(worker, never()).extract(14L);
    }

    @Test
    void listenerRunsOnlyAfterOwningTransactionCommits() throws NoSuchMethodException {
        Method method = InboxAutoActionListener.class.getMethod(
                "onContentReady",
                InboxActionContentReadyEvent.class
        );
        TransactionalEventListener annotation = method.getAnnotation(
                TransactionalEventListener.class
        );

        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
    }
}

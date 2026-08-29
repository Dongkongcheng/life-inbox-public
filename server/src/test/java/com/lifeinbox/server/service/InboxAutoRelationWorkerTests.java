package com.lifeinbox.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboxAutoRelationWorkerTests {

    @Test
    void workerDelegatesAndContainsFailuresAwayFromCaptureLifecycle() {
        RelationProcessingService service = mock(RelationProcessingService.class);
        InboxAutoRelationWorker worker = new InboxAutoRelationWorker(service);
        when(service.processAutomatic(1L)).thenThrow(new IllegalStateException("provider down"));

        assertDoesNotThrow(() -> worker.discover(1L));

        verify(service).processAutomatic(1L);
    }

    @Test
    void claimedBackfillAttemptIsConsumedWithoutEscapingFailures() {
        RelationProcessingService service = mock(RelationProcessingService.class);
        InboxAutoRelationWorker worker = new InboxAutoRelationWorker(service);
        when(service.processClaimed(2L, "attempt-b")).thenThrow(
                new IllegalStateException("provider down")
        );

        assertDoesNotThrow(() -> worker.discoverClaimed(2L, "attempt-b"));

        verify(service).processClaimed(2L, "attempt-b");
    }
}

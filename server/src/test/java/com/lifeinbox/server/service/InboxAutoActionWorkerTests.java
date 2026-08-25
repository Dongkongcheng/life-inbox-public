package com.lifeinbox.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InboxAutoActionWorkerTests {

    private final ActionCandidateService actionCandidateService = mock(ActionCandidateService.class);
    private final InboxAutoActionWorker worker = new InboxAutoActionWorker(actionCandidateService);

    @Test
    void automaticEntryUsesTheSameOrchestratorAsManualApi() {
        worker.extract(20L);

        verify(actionCandidateService).extract(20L);
    }

    @Test
    void actionFailureIsBestEffortAndNeverEscapesBackgroundWorker() {
        doThrow(new IllegalStateException("mock provider failure"))
                .when(actionCandidateService).extract(21L);

        assertDoesNotThrow(() -> worker.extract(21L));
    }
}

package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.RelationPersistenceResult;
import com.lifeinbox.server.dto.RelationProcessingResponse;
import com.lifeinbox.server.entity.RelationProcessingStatus;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RelationProcessingServiceTests {

    private final RelationProcessingStatusService statusService = mock(
            RelationProcessingStatusService.class
    );
    private final RelationDiscoveryPersistenceService persistenceService = mock(
            RelationDiscoveryPersistenceService.class
    );
    private final RelationProcessingService service = new RelationProcessingService(
            statusService,
            persistenceService
    );

    @Test
    void manualProcessingReturnsOnlySuccessAndPersistenceCounts() {
        when(statusService.claimManual(1L)).thenReturn("attempt-a");
        when(persistenceService.discoverTargetIdsForProcessing(1L, null)).thenReturn(
                List.of(2L, 3L)
        );
        when(persistenceService.completeAttempt(1L, "attempt-a", List.of(2L, 3L)))
                .thenReturn(new RelationPersistenceResult(1L, 2, 1, 1, 0, List.of()));

        RelationProcessingResponse response = service.processManual(1L);

        assertEquals(RelationProcessingStatus.SUCCESS, response.relationStatus());
        assertEquals(2, response.discoveredCount());
        assertEquals(1, response.persistedNewCount());
        assertEquals(1, response.alreadyExistingCount());
    }

    @Test
    void automaticProcessingDoesNothingWhenNotProcessedClaimLoses() {
        when(statusService.claimAutomatic(2L)).thenReturn(Optional.empty());

        assertTrue(service.processAutomatic(2L).isEmpty());

        verifyNoInteractions(persistenceService);
    }

    @Test
    void automaticProcessingCompletesSuccessfullyEvenWhenDiscoveryIsEmpty() {
        when(statusService.claimAutomatic(2L)).thenReturn(Optional.of("attempt-auto"));
        when(persistenceService.discoverTargetIdsForProcessing(2L, null)).thenReturn(List.of());
        when(persistenceService.completeAttempt(2L, "attempt-auto", List.of())).thenReturn(
                new RelationPersistenceResult(2L, 0, 0, 0, 0, List.of())
        );

        RelationProcessingResponse response = service.processAutomatic(2L).orElseThrow();

        assertEquals(RelationProcessingStatus.SUCCESS, response.relationStatus());
        assertEquals(0, response.discoveredCount());
    }

    @Test
    void providerFailureMarksOnlyCurrentAttemptFailedAndNeverPersists() {
        when(statusService.claimManual(3L)).thenReturn("attempt-b");
        AiServiceUnavailableException failure = new AiServiceUnavailableException("secret");
        when(persistenceService.discoverTargetIdsForProcessing(3L, null)).thenThrow(failure);

        assertThrows(AiServiceUnavailableException.class, () -> service.processManual(3L));

        verify(statusService).markFailed(3L, "attempt-b", "Relation AI 服务暂时不可用");
        verify(persistenceService, never()).completeAttempt(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void persistenceFailureMarksFailedWithoutRequestingDestructiveCleanup() {
        when(statusService.claimManual(4L)).thenReturn("attempt-c");
        when(persistenceService.discoverTargetIdsForProcessing(4L, null)).thenReturn(
                List.of(5L)
        );
        when(persistenceService.completeAttempt(4L, "attempt-c", List.of(5L)))
                .thenThrow(new IllegalStateException("database down"));

        assertThrows(IllegalStateException.class, () -> service.processManual(4L));

        verify(statusService).markFailed(4L, "attempt-c", "Relation 结果保存失败");
    }

    @Test
    void rediscoveryUsesNewClaimAndKeepsTheAdditiveAttemptPipeline() {
        when(statusService.claimRediscovery(5L)).thenReturn("attempt-new");
        when(persistenceService.discoverTargetIdsForProcessing(5L, null)).thenReturn(
                List.of(8L)
        );
        when(persistenceService.completeAttempt(5L, "attempt-new", List.of(8L)))
                .thenReturn(new RelationPersistenceResult(5L, 1, 1, 0, 0, List.of()));

        RelationProcessingResponse response = service.processRediscovery(5L);

        assertEquals(RelationProcessingStatus.SUCCESS, response.relationStatus());
        assertEquals(1, response.persistedNewCount());
        verify(statusService).claimRediscovery(5L);
    }

    @Test
    void emptyRediscoveryStillCompletesSuccessWithoutDeletingExistingRelations() {
        when(statusService.claimRediscovery(6L)).thenReturn("attempt-empty");
        when(persistenceService.discoverTargetIdsForProcessing(6L, null)).thenReturn(List.of());
        when(persistenceService.completeAttempt(6L, "attempt-empty", List.of()))
                .thenReturn(new RelationPersistenceResult(6L, 0, 0, 0, 0, List.of()));

        RelationProcessingResponse response = service.processRediscovery(6L);

        assertEquals(RelationProcessingStatus.SUCCESS, response.relationStatus());
        verify(persistenceService).completeAttempt(6L, "attempt-empty", List.of());
    }

    @Test
    void failedRediscoveryOnlyMarksItsNewAttemptFailed() {
        when(statusService.claimRediscovery(7L)).thenReturn("attempt-refresh");
        when(persistenceService.discoverTargetIdsForProcessing(7L, null)).thenThrow(
                new AiServiceUnavailableException("provider unavailable")
        );

        assertThrows(
                AiServiceUnavailableException.class,
                () -> service.processRediscovery(7L)
        );

        verify(statusService).markFailed(
                7L,
                "attempt-refresh",
                "Relation AI 服务暂时不可用"
        );
        verify(persistenceService, never()).completeAttempt(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rediscoveryWithMissingVectorReturnsConflictAndNeverMarksSuccess() {
        when(statusService.claimRediscovery(9L)).thenReturn("attempt-vector");
        when(persistenceService.discoverTargetIdsForProcessing(9L, null)).thenThrow(
                new ResponseStatusException(HttpStatus.CONFLICT, "Source Vector 尚未就绪")
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.processRediscovery(9L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(statusService).markFailed(9L, "attempt-vector", "Source Vector 尚未就绪");
        verify(persistenceService, never()).completeAttempt(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void preclaimedBackfillAttemptDoesNotClaimAgain() {
        when(persistenceService.discoverTargetIdsForProcessing(8L, null)).thenReturn(List.of());
        when(persistenceService.completeAttempt(8L, "attempt-backfill", List.of()))
                .thenReturn(new RelationPersistenceResult(8L, 0, 0, 0, 0, List.of()));

        service.processClaimed(8L, "attempt-backfill");

        verifyNoInteractions(statusService);
    }
}

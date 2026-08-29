package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.RelationBackfillResponse;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.RelationProcessingStatus;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RelationBackfillServiceTests {

    private final InboxItemMapper mapper = mock(InboxItemMapper.class);
    private final RelationCandidateDiscoveryService candidateDiscoveryService = mock(
            RelationCandidateDiscoveryService.class
    );
    private final RelationProcessingStatusService statusService = mock(
            RelationProcessingStatusService.class
    );
    private final InboxAutoRelationWorker worker = mock(InboxAutoRelationWorker.class);

    @Test
    void defaultBatchIsBoundedAndSchedulesAtMostTenReadyItems() {
        List<Runnable> tasks = new ArrayList<>();
        TaskExecutor executor = tasks::add;
        RelationBackfillService service = service(executor);
        List<InboxItem> candidates = items(1, 12);
        when(mapper.selectRelationBackfillCandidates(
                RelationProcessingStatus.NOT_PROCESSED,
                50
        )).thenReturn(candidates);
        candidates.forEach(item -> {
            when(candidateDiscoveryService.isSourceVectorReady(item.getId())).thenReturn(true);
            when(statusService.claimAutomatic(item.getId())).thenReturn(
                    Optional.of("attempt-" + item.getId())
            );
        });

        RelationBackfillResponse response = service.schedule(null);

        assertEquals(10, response.requestedLimit());
        assertEquals(10, response.scannedCount());
        assertEquals(10, response.scheduledCount());
        assertEquals(10, tasks.size());
        verify(candidateDiscoveryService, never()).isSourceVectorReady(11L);
    }

    @Test
    void customBatchSkipsMissingVectorsAndClaimRacesWithoutChangingTheirState() {
        List<Runnable> tasks = new ArrayList<>();
        RelationBackfillService service = service(tasks::add);
        when(mapper.selectRelationBackfillCandidates(
                RelationProcessingStatus.NOT_PROCESSED,
                25
        )).thenReturn(items(1, 4));
        when(candidateDiscoveryService.isSourceVectorReady(1L)).thenReturn(false);
        when(candidateDiscoveryService.isSourceVectorReady(2L)).thenReturn(true);
        when(candidateDiscoveryService.isSourceVectorReady(3L)).thenReturn(true);
        when(candidateDiscoveryService.isSourceVectorReady(4L)).thenReturn(false);
        when(statusService.claimAutomatic(2L)).thenReturn(Optional.empty());
        when(statusService.claimAutomatic(3L)).thenReturn(Optional.of("attempt-3"));

        RelationBackfillResponse response = service.schedule(5);

        assertEquals(4, response.scannedCount());
        assertEquals(1, response.scheduledCount());
        assertEquals(2, response.skippedNotReadyCount());
        assertEquals(1, response.claimConflictCount());
        assertEquals(1, tasks.size());
        tasks.getFirst().run();
        verify(worker).discoverClaimed(3L, "attempt-3");
        verify(statusService, never()).claimAutomatic(1L);
        verify(statusService, never()).claimAutomatic(4L);
    }

    @Test
    void invalidLimitsAreRejectedBeforeDatabaseOrVectorAccess() {
        RelationBackfillService service = service(Runnable::run);

        ResponseStatusException low = assertThrows(
                ResponseStatusException.class,
                () -> service.schedule(0)
        );
        ResponseStatusException high = assertThrows(
                ResponseStatusException.class,
                () -> service.schedule(21)
        );

        assertEquals(HttpStatus.BAD_REQUEST, low.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, high.getStatusCode());
        verifyNoInteractions(mapper, candidateDiscoveryService, statusService, worker);
    }

    @Test
    void internalScanIsAlwaysCappedAtOneHundred() {
        assertEquals(5, RelationBackfillService.scanLimitFor(1));
        assertEquals(100, RelationBackfillService.scanLimitFor(20));
    }

    @Test
    void executorRejectionFinishesClaimedAttemptAsFailed() {
        TaskExecutor rejectingExecutor = task -> {
            throw new IllegalStateException("queue full");
        };
        RelationBackfillService service = service(rejectingExecutor);
        when(mapper.selectRelationBackfillCandidates(
                RelationProcessingStatus.NOT_PROCESSED,
                5
        )).thenReturn(items(1, 1));
        when(candidateDiscoveryService.isSourceVectorReady(1L)).thenReturn(true);
        when(statusService.claimAutomatic(1L)).thenReturn(Optional.of("attempt-1"));
        when(statusService.markFailed(1L, "attempt-1", "Relation Backfill 队列繁忙，请稍后重试"))
                .thenReturn(true);

        RelationBackfillResponse response = service.schedule(1);

        assertEquals(0, response.scheduledCount());
        verify(statusService).markFailed(
                1L,
                "attempt-1",
                "Relation Backfill 队列繁忙，请稍后重试"
        );
        verifyNoInteractions(worker);
    }

    private RelationBackfillService service(TaskExecutor executor) {
        return new RelationBackfillService(
                mapper,
                candidateDiscoveryService,
                statusService,
                executor,
                worker
        );
    }

    private List<InboxItem> items(long firstId, long lastId) {
        List<InboxItem> items = new ArrayList<>();
        for (long id = firstId; id <= lastId; id++) {
            InboxItem item = new InboxItem();
            item.setId(id);
            item.setStatus("ACTIVE");
            item.setRelationStatus(RelationProcessingStatus.NOT_PROCESSED);
            items.add(item);
        }
        return items;
    }
}

package com.lifeinbox.server.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.RelationProcessingStatus;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationProcessingStatusServiceTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 29, 12, 0);
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private final InboxItemMapper mapper = mock(InboxItemMapper.class);
    private final RelationProcessingStatusService service = new RelationProcessingStatusService(
            mapper,
            STALE_AFTER,
            Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void newItemHasIndependentRelationStateAndInternalFieldsStayOutOfJson() throws Exception {
        InboxItem item = new InboxItem();

        assertEquals(RelationProcessingStatus.NOT_PROCESSED, item.getRelationStatus());
        assertFalse(item.isRelationProcessingStale());
        for (String fieldName : List.of(
                "relationAttemptId",
                "relationErrorMessage",
                "relationStartedTime",
                "relationFinishedTime"
        )) {
            Field field = InboxItem.class.getDeclaredField(fieldName);
            assertTrue(field.isAnnotationPresent(JsonIgnore.class));
        }
    }

    @Test
    void automaticClaimOnlySucceedsWhenAtomicNotProcessedUpdateWins() {
        when(mapper.markRelationAutomaticProcessing(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(RelationProcessingStatus.NOT_PROCESSED),
                org.mockito.ArgumentMatchers.eq(RelationProcessingStatus.PROCESSING),
                anyString(),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).thenReturn(1, 0);

        assertTrue(service.claimAutomatic(1L).isPresent());
        assertTrue(service.claimAutomatic(1L).isEmpty());
    }

    @Test
    void manualClaimPassesStaleCutoffAndRejectsSuccess() {
        when(mapper.markRelationManualProcessing(
                org.mockito.ArgumentMatchers.eq(2L),
                any(),
                any(),
                any(),
                anyString(),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.eq(NOW.minus(STALE_AFTER))
        )).thenReturn(0);
        InboxItem success = new InboxItem();
        success.setStatus("ACTIVE");
        success.setRelationStatus(RelationProcessingStatus.SUCCESS);
        when(mapper.selectById(2L)).thenReturn(success);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.claimManual(2L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(mapper).markRelationManualProcessing(
                org.mockito.ArgumentMatchers.eq(2L),
                org.mockito.ArgumentMatchers.eq(RelationProcessingStatus.NOT_PROCESSED),
                org.mockito.ArgumentMatchers.eq(RelationProcessingStatus.PROCESSING),
                org.mockito.ArgumentMatchers.eq(RelationProcessingStatus.FAILED),
                anyString(),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.eq(NOW.minus(STALE_AFTER))
        );
    }

    @Test
    void staleRuntimeValueUsesSharedConfiguredThreshold() {
        InboxItem fresh = processingItem(NOW.minusMinutes(4));
        InboxItem stale = processingItem(NOW.minusMinutes(5));
        InboxItem missingStart = processingItem(null);

        assertFalse(service.isProcessingStale(fresh));
        assertTrue(service.isProcessingStale(stale));
        assertTrue(service.isProcessingStale(missingStart));
    }

    @Test
    void failureIsAttemptGuardedAndMessageIsBounded() {
        when(mapper.markRelationFailed(
                org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq("current"),
                org.mockito.ArgumentMatchers.eq(RelationProcessingStatus.PROCESSING),
                org.mockito.ArgumentMatchers.eq(RelationProcessingStatus.FAILED),
                anyString()
        )).thenReturn(1);

        assertTrue(service.markFailed(3L, "current", "错".repeat(300)));
        org.mockito.ArgumentCaptor<String> message = org.mockito.ArgumentCaptor.forClass(
                String.class
        );
        verify(mapper).markRelationFailed(
                org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq("current"),
                org.mockito.ArgumentMatchers.eq(RelationProcessingStatus.PROCESSING),
                org.mockito.ArgumentMatchers.eq(RelationProcessingStatus.FAILED),
                message.capture()
        );
        assertEquals(255, message.getValue().length());
    }

    @Test
    void staleFailureCannotOverwriteNewerAttempt() {
        when(mapper.markRelationFailed(
                4L,
                "attempt-old",
                RelationProcessingStatus.PROCESSING,
                RelationProcessingStatus.FAILED,
                "Relation Discovery 失败"
        )).thenReturn(0);

        assertFalse(service.markFailed(4L, "attempt-old", null));
    }

    private InboxItem processingItem(LocalDateTime startedTime) {
        InboxItem item = new InboxItem();
        item.setRelationStatus(RelationProcessingStatus.PROCESSING);
        item.setRelationStartedTime(startedTime);
        return item;
    }
}

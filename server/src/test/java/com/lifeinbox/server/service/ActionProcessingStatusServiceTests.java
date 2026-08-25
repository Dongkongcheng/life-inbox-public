package com.lifeinbox.server.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lifeinbox.server.entity.ActionProcessingStatus;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionProcessingStatusServiceTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 12, 0);
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-08-25T12:00:00Z"),
            ZoneOffset.UTC
    );
    private final ActionProcessingStatusService service =
            new ActionProcessingStatusService(inboxItemMapper, STALE_AFTER, clock);

    @Test
    void newInboxItemStartsWithIndependentNotProcessedState() {
        InboxItem item = new InboxItem();

        assertEquals(ActionProcessingStatus.NOT_PROCESSED, item.getActionStatus());
        assertNull(item.getActionAttemptId());
        assertNull(item.getActionErrorMessage());
        assertNull(item.getActionStartedTime());
        assertNull(item.getActionFinishedTime());
        assertFalse(item.isActionProcessingStale());
    }

    @Test
    void claimCreatesUniqueUuidAndPassesStaleCutoffToAtomicUpdate() {
        when(inboxItemMapper.markActionProcessing(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(ActionProcessingStatus.PROCESSING),
                anyString(),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(1);

        String first = service.markProcessing(1L);
        String second = service.markProcessing(1L);

        assertNotEquals(first, second);
        assertNotNull(UUID.fromString(first));
        ArgumentCaptor<LocalDateTime> started = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> staleBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(inboxItemMapper, org.mockito.Mockito.times(2)).markActionProcessing(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(ActionProcessingStatus.PROCESSING),
                anyString(),
                started.capture(),
                staleBefore.capture()
        );
        assertEquals(NOW, started.getAllValues().getFirst());
        assertEquals(NOW.minus(STALE_AFTER), staleBefore.getAllValues().getFirst());
    }

    @Test
    void freshProcessingConflictIsRejectedLikeAnalyzeLifecycle() {
        when(inboxItemMapper.markActionProcessing(
                org.mockito.ArgumentMatchers.eq(2L),
                any(),
                anyString(),
                any(),
                any()
        )).thenReturn(0);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.markProcessing(2L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void currentFailureStoresOnlyBoundedSafeMessage() {
        when(inboxItemMapper.markActionFailed(
                org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq("attempt-current"),
                org.mockito.ArgumentMatchers.eq(ActionProcessingStatus.PROCESSING),
                org.mockito.ArgumentMatchers.eq(ActionProcessingStatus.FAILED),
                anyString()
        )).thenReturn(1);

        assertTrue(service.markFailed(3L, "attempt-current", "错".repeat(300)));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(inboxItemMapper).markActionFailed(
                org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq("attempt-current"),
                org.mockito.ArgumentMatchers.eq(ActionProcessingStatus.PROCESSING),
                org.mockito.ArgumentMatchers.eq(ActionProcessingStatus.FAILED),
                message.capture()
        );
        assertEquals(255, message.getValue().length());
    }

    @Test
    void lateFailureCannotMarkNewerAttemptFailed() {
        when(inboxItemMapper.markActionFailed(
                4L,
                "attempt-old",
                ActionProcessingStatus.PROCESSING,
                ActionProcessingStatus.FAILED,
                "Action 提取失败"
        )).thenReturn(0);

        assertFalse(service.markFailed(4L, "attempt-old", null));
    }

    @Test
    void processingBecomesStaleOnlyAtConfiguredRuntimeThreshold() {
        InboxItem fresh = processingItem(NOW.minusMinutes(4));
        InboxItem stale = processingItem(NOW.minusMinutes(5));
        InboxItem missingStart = processingItem(null);
        InboxItem success = new InboxItem();
        success.setActionStatus(ActionProcessingStatus.SUCCESS);

        assertFalse(service.isProcessingStale(fresh));
        assertTrue(service.isProcessingStale(stale));
        assertTrue(service.isProcessingStale(missingStart));
        assertFalse(service.isProcessingStale(success));
    }

    @Test
    void claimAndFailureUseShortSpringTransactions() throws NoSuchMethodException {
        Method claim = ActionProcessingStatusService.class.getMethod("markProcessing", Long.class);
        Method failure = ActionProcessingStatusService.class.getMethod(
                "markFailed",
                Long.class,
                String.class,
                String.class
        );

        assertTrue(claim.isAnnotationPresent(Transactional.class));
        assertTrue(failure.isAnnotationPresent(Transactional.class));
    }

    @Test
    void internalAttemptAndDiagnosticsAreNotExposedByInboxJson() throws NoSuchFieldException {
        for (String fieldName : java.util.List.of(
                "actionAttemptId",
                "actionErrorMessage",
                "actionStartedTime",
                "actionFinishedTime"
        )) {
            Field field = InboxItem.class.getDeclaredField(fieldName);
            assertTrue(field.isAnnotationPresent(JsonIgnore.class));
        }
    }

    private InboxItem processingItem(LocalDateTime startedTime) {
        InboxItem item = new InboxItem();
        item.setActionStatus(ActionProcessingStatus.PROCESSING);
        item.setActionStartedTime(startedTime);
        return item;
    }
}

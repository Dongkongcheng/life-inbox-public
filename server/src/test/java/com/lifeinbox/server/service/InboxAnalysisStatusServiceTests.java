package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.AiProcessingStatus;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboxAnalysisStatusServiceTests {

    private static final Duration STALE_AFTER = Duration.ofMinutes(5);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-20T12:00:00Z"),
            ZoneOffset.UTC
    );
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 12, 0);

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final InboxAnalysisStatusService statusService = new InboxAnalysisStatusService(
            inboxItemMapper,
            STALE_AFTER,
            CLOCK
    );

    @Test
    void newInboxItemDefaultsToNotProcessed() {
        assertEquals(AiProcessingStatus.NOT_PROCESSED, new InboxItem().getAiStatus());
    }

    @Test
    void failedRetryAndSuccessReanalyzeGenerateDifferentAttemptIds() {
        when(inboxItemMapper.markAnalysisProcessing(
                eq(1L),
                eq(AiProcessingStatus.PROCESSING),
                anyString(),
                eq(NOW),
                eq(NOW.minus(STALE_AFTER))
        )).thenReturn(1);

        String retryAttempt = statusService.markProcessing(1L);
        String reanalyzeAttempt = statusService.markProcessing(1L);

        assertEquals(36, retryAttempt.length());
        assertEquals(36, reanalyzeAttempt.length());
        assertNotEquals(retryAttempt, reanalyzeAttempt);
    }

    @Test
    void markProcessingPassesConfiguredStaleCutoffToAtomicUpdate() {
        when(inboxItemMapper.markAnalysisProcessing(
                eq(1L),
                eq(AiProcessingStatus.PROCESSING),
                anyString(),
                eq(NOW),
                eq(NOW.minusMinutes(5))
        )).thenReturn(1);

        String attemptId = statusService.markProcessing(1L);

        ArgumentCaptor<String> attemptCaptor = ArgumentCaptor.forClass(String.class);
        verify(inboxItemMapper).markAnalysisProcessing(
                eq(1L),
                eq(AiProcessingStatus.PROCESSING),
                attemptCaptor.capture(),
                eq(NOW),
                eq(NOW.minusMinutes(5))
        );
        assertEquals(attemptId, attemptCaptor.getValue());
    }

    @Test
    void freshProcessingRequestReturnsConflictWhenAtomicUpdateAcquiresNothing() {
        when(inboxItemMapper.markAnalysisProcessing(
                eq(1L),
                eq(AiProcessingStatus.PROCESSING),
                anyString(),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(0);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> statusService.markProcessing(1L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void twoConcurrentRequestsOnlyOneAcquiresProcessingOwnership() throws Exception {
        AtomicBoolean ownership = new AtomicBoolean();
        when(inboxItemMapper.markAnalysisProcessing(
                eq(1L),
                eq(AiProcessingStatus.PROCESSING),
                anyString(),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenAnswer(invocation -> ownership.compareAndSet(false, true) ? 1 : 0);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> startAnalyzeTogether(start));
            Future<String> second = executor.submit(() -> startAnalyzeTogether(start));
            start.countDown();

            List<String> results = List.of(first.get(), second.get());
            assertEquals(1, results.stream().filter("CONFLICT"::equals).count());
            assertEquals(1, results.stream().filter(result -> result.length() == 36).count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleCalculationUsesStatusStartTimeAndConfiguredThreshold() {
        InboxItem item = new InboxItem();
        item.setAiStatus(AiProcessingStatus.SUCCESS);
        item.setAiStartedTime(NOW.minusHours(1));
        assertFalse(statusService.isProcessingStale(item));

        item.setAiStatus(AiProcessingStatus.PROCESSING);
        item.setAiStartedTime(NOW.minusMinutes(4).minusSeconds(59));
        assertFalse(statusService.isProcessingStale(item));

        item.setAiStartedTime(NOW.minusMinutes(5));
        assertTrue(statusService.isProcessingStale(item));

        item.setAiStartedTime(NOW.minusMinutes(10));
        assertTrue(statusService.isProcessingStale(item));

        item.setAiStartedTime(null);
        assertTrue(statusService.isProcessingStale(item));
    }

    @Test
    void markFailedStoresOnlyShortSafeMessageForCurrentAttempt() {
        String longMessage = "错".repeat(300);
        when(inboxItemMapper.markAnalysisFailed(
                1L,
                "attempt-1",
                AiProcessingStatus.PROCESSING,
                AiProcessingStatus.FAILED,
                "错".repeat(255)
        )).thenReturn(1);

        assertTrue(statusService.markFailed(1L, "attempt-1", longMessage));

        verify(inboxItemMapper).markAnalysisFailed(
                1L,
                "attempt-1",
                AiProcessingStatus.PROCESSING,
                AiProcessingStatus.FAILED,
                "错".repeat(255)
        );
    }

    @Test
    void lateFailureCannotChangeNewAttemptState() {
        when(inboxItemMapper.markAnalysisFailed(
                1L,
                "old-attempt",
                AiProcessingStatus.PROCESSING,
                AiProcessingStatus.FAILED,
                "AI 分析失败"
        )).thenReturn(0);

        assertFalse(statusService.markFailed(1L, "old-attempt", null));
    }

    @Test
    void autoSchedulingFailureOnlyChangesNotProcessedItem() {
        when(inboxItemMapper.markAutoSchedulingFailed(
                1L,
                AiProcessingStatus.NOT_PROCESSED,
                AiProcessingStatus.FAILED,
                "自动分析队列繁忙，请手动重试"
        )).thenReturn(1);

        assertTrue(statusService.markAutoSchedulingFailed(
                1L,
                "自动分析队列繁忙，请手动重试"
        ));

        verify(inboxItemMapper).markAutoSchedulingFailed(
                1L,
                AiProcessingStatus.NOT_PROCESSED,
                AiProcessingStatus.FAILED,
                "自动分析队列繁忙，请手动重试"
        );
    }

    @Test
    void autoSchedulingFailureDoesNotOverwriteAlreadyClaimedItem() {
        when(inboxItemMapper.markAutoSchedulingFailed(
                1L,
                AiProcessingStatus.NOT_PROCESSED,
                AiProcessingStatus.FAILED,
                "AI 分析失败"
        )).thenReturn(0);

        assertFalse(statusService.markAutoSchedulingFailed(1L, null));
    }

    @Test
    void rejectsNonPositiveStaleThreshold() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new InboxAnalysisStatusService(inboxItemMapper, Duration.ZERO, CLOCK)
        );
    }

    @Test
    void stateWritesDefineIndependentShortTransactions() throws NoSuchMethodException {
        Method processing = InboxAnalysisStatusService.class.getMethod("markProcessing", Long.class);
        Method failed = InboxAnalysisStatusService.class.getMethod(
                "markFailed",
                Long.class,
                String.class,
                String.class
        );
        Method schedulingFailed = InboxAnalysisStatusService.class.getMethod(
                "markAutoSchedulingFailed",
                Long.class,
                String.class
        );

        assertTrue(processing.isAnnotationPresent(Transactional.class));
        assertTrue(failed.isAnnotationPresent(Transactional.class));
        Transactional schedulingTransaction = schedulingFailed.getAnnotation(
                Transactional.class
        );
        assertEquals(Propagation.REQUIRES_NEW, schedulingTransaction.propagation());
    }

    private String startAnalyzeTogether(CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            return statusService.markProcessing(1L);
        } catch (ResponseStatusException exception) {
            assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
            return "CONFLICT";
        }
    }
}

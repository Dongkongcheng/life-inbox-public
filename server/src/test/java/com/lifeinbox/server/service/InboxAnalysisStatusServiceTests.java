package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.AiProcessingStatus;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboxAnalysisStatusServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final InboxAnalysisStatusService statusService = new InboxAnalysisStatusService(
            inboxItemMapper
    );

    @Test
    void newInboxItemDefaultsToNotProcessed() {
        assertEquals(AiProcessingStatus.NOT_PROCESSED, new InboxItem().getAiStatus());
    }

    @Test
    void markProcessingUsesAtomicConditionalUpdate() {
        when(inboxItemMapper.markAnalysisProcessing(
                1L,
                AiProcessingStatus.PROCESSING
        )).thenReturn(1);

        statusService.markProcessing(1L);

        verify(inboxItemMapper).markAnalysisProcessing(1L, AiProcessingStatus.PROCESSING);
    }

    @Test
    void duplicateProcessingRequestReturnsConflict() {
        when(inboxItemMapper.markAnalysisProcessing(
                1L,
                AiProcessingStatus.PROCESSING
        )).thenReturn(0);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> statusService.markProcessing(1L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void markFailedStoresOnlyShortSafeMessage() {
        String longMessage = "错".repeat(300);
        when(inboxItemMapper.markAnalysisFailed(
                1L,
                AiProcessingStatus.PROCESSING,
                AiProcessingStatus.FAILED,
                "错".repeat(255)
        )).thenReturn(1);

        statusService.markFailed(1L, longMessage);

        verify(inboxItemMapper).markAnalysisFailed(
                1L,
                AiProcessingStatus.PROCESSING,
                AiProcessingStatus.FAILED,
                "错".repeat(255)
        );
    }

    @Test
    void stateWritesDefineIndependentShortTransactions() throws NoSuchMethodException {
        Method processing = InboxAnalysisStatusService.class.getMethod("markProcessing", Long.class);
        Method failed = InboxAnalysisStatusService.class.getMethod(
                "markFailed",
                Long.class,
                String.class
        );

        assertTrue(processing.isAnnotationPresent(Transactional.class));
        assertTrue(failed.isAnnotationPresent(Transactional.class));
    }
}

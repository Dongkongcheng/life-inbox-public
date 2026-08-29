package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.AiVectorDeleteResponse;
import com.lifeinbox.server.dto.AiVectorIndexResponse;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.event.InboxVectorReadyEvent;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class InboxVectorIndexWorkerTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final InboxSearchableContentService searchableContentService = mock(
            InboxSearchableContentService.class
    );
    private final AiServiceClient aiServiceClient = mock(AiServiceClient.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final InboxVectorIndexWorker worker = new InboxVectorIndexWorker(
            inboxItemMapper,
            searchableContentService,
            aiServiceClient,
            eventPublisher
    );

    @Test
    void currentAttemptIndexesLatestActiveSearchableContent() {
        InboxItem item = item("ACTIVE", "attempt-b");
        when(inboxItemMapper.selectById(1L)).thenReturn(item);
        when(searchableContentService.resolveForRetrieval(item)).thenReturn("最新正文 B");
        when(aiServiceClient.indexVector(1L, "最新正文 B")).thenReturn(
                indexedResponse(1L)
        );

        worker.index(1L, "attempt-b");

        verify(aiServiceClient).indexVector(1L, "最新正文 B");
        verify(eventPublisher).publishEvent(any(InboxVectorReadyEvent.class));
    }

    @Test
    void lateAttemptCannotOverwriteNewerVector() {
        InboxItem item = item("ACTIVE", "attempt-b");
        when(inboxItemMapper.selectById(1L)).thenReturn(item);

        worker.index(1L, "attempt-a");

        verifyNoInteractions(searchableContentService, aiServiceClient);
    }

    @Test
    void captureTriggerOnlyOwnsItemsNotYetClaimedByAnalyze() {
        InboxItem untouchedText = item("ACTIVE", null);
        when(inboxItemMapper.selectById(1L)).thenReturn(untouchedText);
        when(searchableContentService.resolveForRetrieval(untouchedText)).thenReturn("TEXT 正文");
        when(aiServiceClient.indexVector(1L, "TEXT 正文")).thenReturn(indexedResponse(1L));

        worker.index(1L, null);

        verify(aiServiceClient).indexVector(1L, "TEXT 正文");
    }

    @Test
    void archivedOrMissingContentCannotBeIndexed() {
        InboxItem archived = item("ARCHIVED", "attempt-a");
        when(inboxItemMapper.selectById(1L)).thenReturn(archived);

        worker.index(1L, "attempt-a");

        verifyNoInteractions(searchableContentService, aiServiceClient);

        InboxItem activeWithoutContent = item("ACTIVE", "attempt-b");
        when(inboxItemMapper.selectById(2L)).thenReturn(activeWithoutContent);
        when(searchableContentService.resolveForRetrieval(activeWithoutContent)).thenReturn(null);

        worker.index(2L, "attempt-b");

        verify(aiServiceClient, never()).indexVector(2L, null);
    }

    @Test
    void vectorFailuresNeverEscapeWorkerOrChangeBusinessState() {
        InboxItem item = item("ACTIVE", "attempt-a");
        when(inboxItemMapper.selectById(1L)).thenReturn(item);
        when(searchableContentService.resolveForRetrieval(item)).thenReturn("正文");
        when(aiServiceClient.indexVector(1L, "正文"))
                .thenThrow(new IllegalStateException("Qdrant unavailable"));
        when(aiServiceClient.deleteVector(1L))
                .thenThrow(new IllegalStateException("Qdrant unavailable"));

        assertDoesNotThrow(() -> worker.index(1L, "attempt-a"));
        assertDoesNotThrow(() -> worker.delete(1L));

        verify(inboxItemMapper).selectById(1L);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void disabledVectorStoreDoesNotPublishReadyEvent() {
        InboxItem item = item("ACTIVE", "attempt-a");
        when(inboxItemMapper.selectById(1L)).thenReturn(item);
        when(searchableContentService.resolveForRetrieval(item)).thenReturn("正文");
        when(aiServiceClient.indexVector(1L, "正文")).thenReturn(new AiVectorIndexResponse(
                1L,
                false,
                null,
                null,
                null,
                null
        ));

        worker.index(1L, "attempt-a");

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void deleteDelegatesToIdempotentPythonEndpoint() {
        when(aiServiceClient.deleteVector(1L)).thenReturn(
                new AiVectorDeleteResponse(1L, true)
        );

        worker.delete(1L);

        verify(aiServiceClient).deleteVector(1L);
        verify(inboxItemMapper, never()).deleteById(1L);
    }

    private InboxItem item(String status, String attemptId) {
        InboxItem item = new InboxItem();
        item.setId(1L);
        item.setType("TEXT");
        item.setContent("正文");
        item.setStatus(status);
        item.setAiAttemptId(attemptId);
        return item;
    }

    private AiVectorIndexResponse indexedResponse(Long inboxItemId) {
        return new AiVectorIndexResponse(
                inboxItemId,
                true,
                "items__model__d_3",
                "embedding-model",
                3,
                "a".repeat(64)
        );
    }
}

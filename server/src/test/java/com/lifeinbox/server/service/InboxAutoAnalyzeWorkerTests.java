package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboxAutoAnalyzeWorkerTests {

    @Test
    void workerReusesUnifiedAnalyzeService() {
        InboxAnalyzeService analyzeService = mock(InboxAnalyzeService.class);
        InboxAutoAnalyzeWorker worker = new InboxAutoAnalyzeWorker(analyzeService);

        worker.analyze(10L);

        verify(analyzeService).analyze(10L);
    }

    @Test
    void analyzeFailureCannotEscapeTheBackgroundWorker() {
        InboxAnalyzeService analyzeService = mock(InboxAnalyzeService.class);
        InboxAutoAnalyzeWorker worker = new InboxAutoAnalyzeWorker(analyzeService);
        doThrow(new IllegalStateException("LLM unavailable")).when(analyzeService).analyze(11L);

        assertDoesNotThrow(() -> worker.analyze(11L));
    }

    @Test
    void automaticLlmFailureUsesExistingFailedStateFlow() {
        InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
        AiServiceClient aiServiceClient = mock(AiServiceClient.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        InboxAnalysisStatusService statusService = mock(InboxAnalysisStatusService.class);
        InboxAnalysisPersistenceService persistenceService = mock(
                InboxAnalysisPersistenceService.class
        );
        InboxSearchableContentService searchableContentService = mock(
                InboxSearchableContentService.class
        );
        InboxVectorIndexScheduler vectorIndexScheduler = mock(InboxVectorIndexScheduler.class);
        InboxAnalyzeService analyzeService = new InboxAnalyzeService(
                inboxItemMapper,
                aiServiceClient,
                fileStorageService,
                statusService,
                persistenceService,
                searchableContentService,
                vectorIndexScheduler
        );
        InboxAutoAnalyzeWorker worker = new InboxAutoAnalyzeWorker(analyzeService);
        InboxItem item = new InboxItem();
        item.setId(12L);
        item.setType("TEXT");
        item.setContent("需要自动分析的正文");
        when(inboxItemMapper.selectById(12L)).thenReturn(item);
        when(statusService.markProcessing(12L)).thenReturn("attempt-12");
        when(searchableContentService.prepareText("需要自动分析的正文"))
                .thenReturn("需要自动分析的正文");
        when(aiServiceClient.analyze(null, "需要自动分析的正文"))
                .thenThrow(new IllegalStateException("LLM unavailable"));
        when(statusService.markFailed(12L, "attempt-12", "AI 分析失败")).thenReturn(true);

        assertDoesNotThrow(() -> worker.analyze(12L));

        verify(statusService).markProcessing(12L);
        verify(statusService).markFailed(12L, "attempt-12", "AI 分析失败");
        verify(persistenceService, never()).replaceAnalysis(
                any(), any(), any(), any(), any(), any(), any()
        );
    }
}

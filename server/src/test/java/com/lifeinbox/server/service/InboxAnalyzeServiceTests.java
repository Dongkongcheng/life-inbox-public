package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.AiAnalyzeResponse;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InboxAnalyzeServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final AiServiceClient aiServiceClient = mock(AiServiceClient.class);
    private final InboxAnalysisPersistenceService persistenceService = mock(
            InboxAnalysisPersistenceService.class
    );
    private final InboxAnalyzeService analyzeService = new InboxAnalyzeService(
            inboxItemMapper,
            aiServiceClient,
            persistenceService
    );

    @Test
    void rejectsMissingInboxItem() {
        when(inboxItemMapper.selectById(99L)).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> analyzeService.analyze(99L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(aiServiceClient, persistenceService);
    }

    @Test
    void rejectsNonTextItem() {
        when(inboxItemMapper.selectById(1L)).thenReturn(item("URL", null));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> analyzeService.analyze(1L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(aiServiceClient, persistenceService);
    }

    @Test
    void rejectsBlankTextContent() {
        when(inboxItemMapper.selectById(1L)).thenReturn(item("TEXT", "  "));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> analyzeService.analyze(1L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(aiServiceClient, persistenceService);
    }

    @Test
    void rejectsTextOverCharacterLimitBeforeCallingAi() {
        when(inboxItemMapper.selectById(1L)).thenReturn(item("TEXT", "x".repeat(20_001)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> analyzeService.analyze(1L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(aiServiceClient, persistenceService);
    }

    @Test
    void validatesAndNormalizesCompleteAnalysisBeforePersistence() {
        InboxItem original = item("TEXT", "原始正文");
        original.setTitle("学习笔记");
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        when(aiServiceClient.analyze("学习笔记", "原始正文")).thenReturn(
                new AiAnalyzeResponse(
                        "  新的摘要  ",
                        "技术学习",
                        List.of("Java", " java ", "Spring　AI")
                )
        );
        InboxItem updated = item("TEXT", "原始正文");
        updated.setSummary("新的摘要");
        updated.setCategory("技术学习");
        updated.setTags(List.of("Java", "Spring AI"));
        List<NormalizedTag> expectedTags = List.of(
                new NormalizedTag("Java", "java"),
                new NormalizedTag("Spring AI", "spring ai")
        );
        when(persistenceService.replaceAnalysis(
                1L,
                "新的摘要",
                "技术学习",
                expectedTags
        )).thenReturn(updated);

        InboxItem result = analyzeService.analyze(1L);

        assertEquals(updated, result);
        verify(aiServiceClient).analyze("学习笔记", "原始正文");
        verify(persistenceService).replaceAnalysis(
                1L,
                "新的摘要",
                "技术学习",
                expectedTags
        );
    }

    @Test
    void rejectsCategoryOutsideFiniteSet() {
        prepareResponse(new AiAnalyzeResponse("摘要", "Java后端", List.of("Java")));

        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        verify(persistenceService, never()).replaceAnalysis(anyLong(), any(), any(), any());
    }

    @Test
    void rejectsBlankOrOverlongSummary() {
        prepareResponse(new AiAnalyzeResponse("  ", "其他", List.of("记录")));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(new AiAnalyzeResponse("x".repeat(2_001), "其他", List.of("记录")));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        verify(persistenceService, never()).replaceAnalysis(anyLong(), any(), any(), any());
    }

    @Test
    void rejectsMissingOrTooManyTags() {
        prepareResponse(new AiAnalyzeResponse("摘要", "其他", null));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(new AiAnalyzeResponse("摘要", "其他", List.of()));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(new AiAnalyzeResponse(
                "摘要",
                "其他",
                List.of("一", "二", "三", "四", "五", "六")
        ));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        verify(persistenceService, never()).replaceAnalysis(anyLong(), any(), any(), any());
    }

    @Test
    void rejectsBlankOrOverlongTag() {
        prepareResponse(new AiAnalyzeResponse("摘要", "其他", List.of("  ")));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(new AiAnalyzeResponse("摘要", "其他", List.of("x".repeat(65))));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        verify(persistenceService, never()).replaceAnalysis(anyLong(), any(), any(), any());
    }

    @Test
    void aiFailureKeepsOldAnalysisAndNeverStartsPersistence() {
        InboxItem original = item("TEXT", "原始正文");
        original.setSummary("旧摘要");
        original.setCategory("工作");
        original.setTags(List.of("旧标签"));
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        when(aiServiceClient.analyze(null, "原始正文"))
                .thenThrow(new AiServiceUnavailableException("mock AI failure"));

        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        assertEquals("旧摘要", original.getSummary());
        assertEquals("工作", original.getCategory());
        assertEquals(List.of("旧标签"), original.getTags());
        verifyNoInteractions(persistenceService);
    }

    private void prepareResponse(AiAnalyzeResponse response) {
        when(inboxItemMapper.selectById(1L)).thenReturn(item("TEXT", "正文"));
        when(aiServiceClient.analyze(null, "正文")).thenReturn(response);
    }

    private InboxItem item(String type, String content) {
        InboxItem item = new InboxItem();
        item.setId(1L);
        item.setType(type);
        item.setContent(content);
        return item;
    }
}

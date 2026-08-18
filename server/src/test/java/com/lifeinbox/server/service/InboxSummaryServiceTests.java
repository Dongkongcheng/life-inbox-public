package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.AiSummaryResponse;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

class InboxSummaryServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final AiServiceClient aiServiceClient = mock(AiServiceClient.class);
    private final InboxSummaryService summaryService = new InboxSummaryService(
            inboxItemMapper,
            aiServiceClient
    );

    @Test
    void rejectsMissingInboxItem() {
        when(inboxItemMapper.selectById(99L)).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> summaryService.generateSummary(99L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(aiServiceClient);
        verify(inboxItemMapper, never()).updateSummary(anyLong(), anyString());
    }

    @Test
    void rejectsNonTextItem() {
        InboxItem item = item(1L, "URL", null, null);
        when(inboxItemMapper.selectById(1L)).thenReturn(item);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> summaryService.generateSummary(1L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(aiServiceClient);
        verify(inboxItemMapper, never()).updateSummary(anyLong(), anyString());
    }

    @Test
    void rejectsBlankTextContent() {
        InboxItem item = item(1L, "TEXT", "  ", null);
        when(inboxItemMapper.selectById(1L)).thenReturn(item);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> summaryService.generateSummary(1L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(aiServiceClient);
        verify(inboxItemMapper, never()).updateSummary(anyLong(), anyString());
    }

    @Test
    void rejectsTextOverCharacterLimitBeforeCallingAi() {
        InboxItem item = item(1L, "TEXT", "x".repeat(20_001), null);
        when(inboxItemMapper.selectById(1L)).thenReturn(item);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> summaryService.generateSummary(1L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(aiServiceClient);
        verify(inboxItemMapper, never()).updateSummary(anyLong(), anyString());
    }

    @Test
    void overwritesOldSummaryWithValidatedResultAndReturnsUpdatedItem() {
        InboxItem original = item(1L, "TEXT", "原始正文", "旧摘要");
        original.setTitle("学习笔记");
        InboxItem updated = item(1L, "TEXT", "原始正文", "新的摘要");
        when(inboxItemMapper.selectById(1L)).thenReturn(original, updated);
        when(aiServiceClient.summarize("学习笔记", "原始正文"))
                .thenReturn(new AiSummaryResponse("  新的摘要  "));
        when(inboxItemMapper.updateSummary(1L, "新的摘要")).thenReturn(1);

        InboxItem result = summaryService.generateSummary(1L);

        assertEquals(updated, result);
        assertEquals("新的摘要", result.getSummary());
        assertEquals("旧摘要", original.getSummary());
        verify(inboxItemMapper).updateSummary(1L, "新的摘要");
    }

    @Test
    void aiFailureKeepsOriginalItemAndOldSummaryUntouched() {
        InboxItem original = item(1L, "TEXT", "原始正文", "旧摘要");
        original.setTitle("原始标题");
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        when(aiServiceClient.summarize("原始标题", "原始正文"))
                .thenThrow(new AiServiceUnavailableException("mock AI failure"));

        assertThrows(
                AiServiceUnavailableException.class,
                () -> summaryService.generateSummary(1L)
        );

        assertEquals("原始标题", original.getTitle());
        assertEquals("原始正文", original.getContent());
        assertEquals("旧摘要", original.getSummary());
        verify(inboxItemMapper, never()).updateSummary(anyLong(), anyString());
    }

    @Test
    void rejectsBlankAiSummaryWithoutUpdatingDatabase() {
        InboxItem original = item(1L, "TEXT", "原始正文", "旧摘要");
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        when(aiServiceClient.summarize(null, "原始正文"))
                .thenReturn(new AiSummaryResponse("  "));

        assertThrows(
                AiServiceUnavailableException.class,
                () -> summaryService.generateSummary(1L)
        );

        verify(inboxItemMapper, never()).updateSummary(anyLong(), anyString());
        assertEquals("旧摘要", original.getSummary());
    }

    @Test
    void rejectsOverlongAiSummaryWithoutUpdatingDatabase() {
        InboxItem original = item(1L, "TEXT", "原始正文", "旧摘要");
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        when(aiServiceClient.summarize(null, "原始正文"))
                .thenReturn(new AiSummaryResponse("x".repeat(2_001)));

        assertThrows(
                AiServiceUnavailableException.class,
                () -> summaryService.generateSummary(1L)
        );

        verify(inboxItemMapper, never()).updateSummary(anyLong(), anyString());
        assertEquals("旧摘要", original.getSummary());
    }

    private InboxItem item(Long id, String type, String content, String summary) {
        InboxItem item = new InboxItem();
        item.setId(id);
        item.setType(type);
        item.setContent(content);
        item.setSummary(summary);
        return item;
    }
}

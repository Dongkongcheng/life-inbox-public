package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.AiProcessingStatus;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

class InboxSearchableContentServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final InboxSearchableContentService service = new InboxSearchableContentService(
            inboxItemMapper
    );

    @Test
    void textUsesOriginalContentWithoutWritingADuplicateDerivedValue() {
        InboxItem item = item("TEXT", "  Redis　分布式锁\r\n\r\n\r\n第二\t行\u0000测试  ");

        assertEquals("Redis 分布式锁\n\n第二 行 测试", service.resolveForRetrieval(item));
        verify(inboxItemMapper, never()).updateSearchableContent(anyLong(), any(), any(), any());
    }

    @Test
    void extractedContentIsNormalizedAndWrittenWithAttemptGuard() {
        when(inboxItemMapper.updateSearchableContent(
                8L,
                "attempt-b",
                AiProcessingStatus.PROCESSING,
                "第一页\n\n第二页"
        )).thenReturn(1);

        String result = service.replaceExtractedContent(
                8L,
                "attempt-b",
                "  第一页\r\n\r\n\r\n 第二页  "
        );

        assertEquals("第一页\n\n第二页", result);
        verify(inboxItemMapper).updateSearchableContent(
                8L,
                "attempt-b",
                AiProcessingStatus.PROCESSING,
                "第一页\n\n第二页"
        );
    }

    @Test
    void staleAttemptCannotOverwriteNewerSearchableContent() {
        when(inboxItemMapper.updateSearchableContent(
                8L,
                "attempt-a",
                AiProcessingStatus.PROCESSING,
                "迟到正文"
        )).thenReturn(0);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.replaceExtractedContent(8L, "attempt-a", "迟到正文")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void successfulReanalysisReplacesThePreviousDerivedContent() {
        AtomicReference<String> storedContent = new AtomicReference<>("正文 A");
        when(inboxItemMapper.updateSearchableContent(anyLong(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    storedContent.set(invocation.getArgument(3));
                    return 1;
                });

        service.replaceExtractedContent(8L, "attempt-b", "正文 B");

        assertEquals("正文 B", storedContent.get());
    }

    @Test
    void invalidNewExtractionDoesNotIssueAnUpdateSoOldContentCanRemain() {
        assertThrows(
                AiServiceUnavailableException.class,
                () -> service.replaceExtractedContent(8L, "attempt-b", " \n\t ")
        );

        verify(inboxItemMapper, never()).updateSearchableContent(anyLong(), any(), any(), any());
    }

    @Test
    void retrievalAllowsOldItemsWithoutPreparedContent() {
        InboxItem item = item("URL", null);
        item.setSearchableContent(null);

        assertNull(service.resolveForRetrieval(item));
    }

    @Test
    void textOverUnifiedLimitIsRejected() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.prepareText("x".repeat(20_001))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    private InboxItem item(String type, String content) {
        InboxItem item = new InboxItem();
        item.setType(type);
        item.setContent(content);
        return item;
    }
}

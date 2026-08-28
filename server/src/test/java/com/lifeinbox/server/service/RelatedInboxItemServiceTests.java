package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.RelatedInboxItemResponse;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.RelationType;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RelatedInboxItemServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final RelatedInboxItemService service = new RelatedInboxItemService(inboxItemMapper);

    @Test
    void returnsOneRelatedItemThroughMinimalProductDto() {
        InboxItem source = item(100L, "TEXT", "Source", "ACTIVE");
        InboxItem related = item(200L, "TEXT", "Redisson", "ACTIVE");
        related.setContent("  分布式锁\n使用说明  ");
        related.setSummary("Redisson 分布式锁摘要");
        related.setCategory("技术学习");
        related.setFavorite(1);
        related.setCreatedTime(LocalDateTime.of(2026, 8, 28, 10, 0));
        stub(source, 10, List.of(related));

        List<RelatedInboxItemResponse> result = service.listRelated(100L, null);

        assertEquals(1, result.size());
        RelatedInboxItemResponse response = result.getFirst();
        assertEquals(RelationType.RELATED_TO, response.relationType());
        assertEquals(200L, response.relatedInboxItem().id());
        assertEquals("TEXT", response.relatedInboxItem().type());
        assertEquals("Redisson", response.relatedInboxItem().title());
        assertEquals("Redisson 分布式锁摘要", response.relatedInboxItem().summary());
        assertEquals("技术学习", response.relatedInboxItem().category());
        assertEquals("分布式锁 使用说明", response.relatedInboxItem().preview());
        assertTrue(response.relatedInboxItem().favorite());
        assertEquals(related.getCreatedTime(), response.relatedInboxItem().createdTime());
        verify(inboxItemMapper).selectById(100L);
        verify(inboxItemMapper).selectRelatedActiveItems(
                100L,
                RelationType.RELATED_TO,
                10
        );
        verifyNoMoreInteractions(inboxItemMapper);
    }

    @Test
    void multipleRelatedItemsPreserveOrderingWithOneBoundedQueryAndNoNPlusOne() {
        InboxItem source = item(100L, "TEXT", "Source", "ACTIVE");
        InboxItem newest = item(300L, "TEXT", "Newest", "ACTIVE");
        InboxItem middle = item(200L, "TEXT", "Middle", "ACTIVE");
        InboxItem oldest = item(150L, "TEXT", "Oldest", "ACTIVE");
        stub(source, 3, List.of(newest, middle, oldest));

        List<RelatedInboxItemResponse> result = service.listRelated(100L, 3);

        assertEquals(List.of(300L, 200L, 150L), result.stream()
                .map(response -> response.relatedInboxItem().id())
                .toList());
        verify(inboxItemMapper).selectRelatedActiveItems(
                100L,
                RelationType.RELATED_TO,
                3
        );
        verify(inboxItemMapper).selectById(100L);
        verifyNoMoreInteractions(inboxItemMapper);
    }

    @Test
    void activeSourceWithoutRelationsReturnsEmptyList() {
        InboxItem source = item(100L, "TEXT", "Source", "ACTIVE");
        stub(source, 10, List.of());

        assertEquals(List.of(), service.listRelated(100L, null));
        verify(inboxItemMapper).selectById(100L);
        verify(inboxItemMapper).selectRelatedActiveItems(
                100L,
                RelationType.RELATED_TO,
                10
        );
        verifyNoMoreInteractions(inboxItemMapper);
    }

    @Test
    void missingOrArchivedSourceUsesCurrentActiveOnlyNotFoundConvention() {
        when(inboxItemMapper.selectById(100L)).thenReturn(null);
        ResponseStatusException missing = assertThrows(
                ResponseStatusException.class,
                () -> service.listRelated(100L, null)
        );
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());

        InboxItem archived = item(101L, "TEXT", "Archived", "ARCHIVED");
        when(inboxItemMapper.selectById(101L)).thenReturn(archived);
        ResponseStatusException archivedFailure = assertThrows(
                ResponseStatusException.class,
                () -> service.listRelated(101L, null)
        );
        assertEquals(HttpStatus.NOT_FOUND, archivedFailure.getStatusCode());

        verify(inboxItemMapper, never()).selectRelatedActiveItems(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void defensivelySkipsArchivedMissingSelfAndInvalidTargets() {
        InboxItem source = item(100L, "TEXT", "Source", "ACTIVE");
        InboxItem active = item(200L, "TEXT", "Active", "ACTIVE");
        InboxItem archived = item(300L, "TEXT", "Archived", "ARCHIVED");
        InboxItem self = item(100L, "TEXT", "Source", "ACTIVE");
        InboxItem invalid = item(null, "TEXT", "Invalid", "ACTIVE");
        stub(source, 10, Arrays.asList(active, archived, null, self, invalid));

        List<RelatedInboxItemResponse> result = service.listRelated(100L, null);

        assertEquals(1, result.size());
        assertEquals(200L, result.getFirst().relatedInboxItem().id());
        verify(inboxItemMapper).selectById(100L);
        verify(inboxItemMapper).selectRelatedActiveItems(
                100L,
                RelationType.RELATED_TO,
                10
        );
        verifyNoMoreInteractions(inboxItemMapper);
    }

    @Test
    void duplicateRelatedItemsAreReturnedOnlyOnce() {
        InboxItem source = item(100L, "TEXT", "Source", "ACTIVE");
        InboxItem first = item(200L, "TEXT", "First", "ACTIVE");
        InboxItem duplicate = item(200L, "TEXT", "Duplicate", "ACTIVE");
        stub(source, 10, List.of(first, duplicate));

        List<RelatedInboxItemResponse> result = service.listRelated(100L, null);

        assertEquals(1, result.size());
        assertEquals("First", result.getFirst().relatedInboxItem().title());
    }

    @Test
    void defaultAndCustomLimitsAreAppliedToTheDatabaseQuery() {
        InboxItem source = item(100L, "TEXT", "Source", "ACTIVE");
        when(inboxItemMapper.selectById(100L)).thenReturn(source);
        when(inboxItemMapper.selectRelatedActiveItems(
                100L,
                RelationType.RELATED_TO,
                RelatedInboxItemService.DEFAULT_LIMIT
        )).thenReturn(List.of());
        when(inboxItemMapper.selectRelatedActiveItems(
                100L,
                RelationType.RELATED_TO,
                5
        )).thenReturn(List.of());

        service.listRelated(100L, null);
        service.listRelated(100L, 5);

        verify(inboxItemMapper).selectRelatedActiveItems(
                100L,
                RelationType.RELATED_TO,
                10
        );
        verify(inboxItemMapper).selectRelatedActiveItems(
                100L,
                RelationType.RELATED_TO,
                5
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 21, -1})
    void invalidLimitIsRejectedBeforeAnyDatabaseQuery(int invalidLimit) {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.listRelated(100L, invalidLimit)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(inboxItemMapper);
    }

    @Test
    void previewIsBoundedByUnicodeCodePointsWithoutSplittingEmoji() {
        InboxItem source = item(100L, "TEXT", "Source", "ACTIVE");
        InboxItem related = item(200L, "TEXT", "Related", "ACTIVE");
        related.setContent("😀".repeat(InboxItemPreviewBuilder.MAX_PREVIEW_CHARS + 20));
        stub(source, 10, List.of(related));

        String preview = service.listRelated(100L, null)
                .getFirst()
                .relatedInboxItem()
                .preview();

        assertEquals(InboxItemPreviewBuilder.MAX_PREVIEW_CHARS,
                preview.codePointCount(0, preview.length()));
        assertTrue(preview.endsWith("…"));
    }

    @ParameterizedTest
    @CsvSource({"URL", "FILE", "IMAGE"})
    void nonTextPreviewUsesPersistedSearchableContentOnly(String type) {
        InboxItem source = item(100L, "TEXT", "Source", "ACTIVE");
        InboxItem related = item(200L, type, "Title fallback", "ACTIVE");
        related.setContent("原始字段不应进入预览");
        related.setSearchableContent(" 已持久化\n正文 ");
        stub(source, 10, List.of(related));

        RelatedInboxItemResponse response = service.listRelated(100L, null).getFirst();

        assertEquals("已持久化 正文", response.relatedInboxItem().preview());
        assertFalse(response.relatedInboxItem().favorite());

        related.setSearchableContent(" ");
        assertEquals("Title fallback",
                service.listRelated(100L, null).getFirst().relatedInboxItem().preview());
    }

    @Test
    void nullMapperResultIsAControlledServerFailure() {
        InboxItem source = item(100L, "TEXT", "Source", "ACTIVE");
        when(inboxItemMapper.selectById(100L)).thenReturn(source);
        when(inboxItemMapper.selectRelatedActiveItems(
                100L,
                RelationType.RELATED_TO,
                10
        )).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.listRelated(100L, null)
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
    }

    @Test
    void readServiceHasOnlyMysqlReadDependencyAndNoTransactionBoundary() throws Exception {
        assertEquals(
                List.of(InboxItemMapper.class),
                Arrays.asList(RelatedInboxItemService.class
                        .getConstructors()[0]
                        .getParameterTypes())
        );
        Method method = RelatedInboxItemService.class.getMethod(
                "listRelated",
                Long.class,
                Integer.class
        );
        assertNull(RelatedInboxItemService.class.getAnnotation(Transactional.class));
        assertNull(method.getAnnotation(Transactional.class));
    }

    private void stub(InboxItem source, int limit, List<InboxItem> relatedItems) {
        when(inboxItemMapper.selectById(source.getId())).thenReturn(source);
        when(inboxItemMapper.selectRelatedActiveItems(
                source.getId(),
                RelationType.RELATED_TO,
                limit
        )).thenReturn(relatedItems);
    }

    private InboxItem item(Long id, String type, String title, String status) {
        InboxItem item = new InboxItem();
        item.setId(id);
        item.setType(type);
        item.setTitle(title);
        item.setStatus(status);
        item.setFavorite(0);
        return item;
    }
}

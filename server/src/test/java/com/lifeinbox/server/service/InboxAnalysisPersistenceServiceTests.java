package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.AiEntityResponse;
import com.lifeinbox.server.entity.InboxEntity;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxEntityMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import com.lifeinbox.server.mapper.InboxKeywordMapper;
import com.lifeinbox.server.mapper.InboxTagMapper;
import com.lifeinbox.server.mapper.TagMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboxAnalysisPersistenceServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final TagMapper tagMapper = mock(TagMapper.class);
    private final InboxTagMapper inboxTagMapper = mock(InboxTagMapper.class);
    private final InboxKeywordMapper inboxKeywordMapper = mock(InboxKeywordMapper.class);
    private final InboxEntityMapper inboxEntityMapper = mock(InboxEntityMapper.class);
    private final InboxAnalysisPersistenceService persistenceService =
            new InboxAnalysisPersistenceService(
                    inboxItemMapper,
                    tagMapper,
                    inboxTagMapper,
                    inboxKeywordMapper,
                    inboxEntityMapper
            );

    @Test
    void completeAnalysisReplacesAllChildResultsAndReturnsAggregates() {
        List<NormalizedTag> tags = List.of(
                new NormalizedTag("Java", "java"),
                new NormalizedTag("Spring AI", "spring ai")
        );
        when(inboxItemMapper.updateAnalysis(1L, "摘要", "技术学习")).thenReturn(1);
        when(tagMapper.selectIdByNormalizedName("java")).thenReturn(10L);
        when(tagMapper.selectIdByNormalizedName("spring ai")).thenReturn(11L);
        when(inboxTagMapper.insertRelation(1L, 10L)).thenReturn(1);
        when(inboxTagMapper.insertRelation(1L, 11L)).thenReturn(1);
        when(inboxKeywordMapper.insertKeyword(1L, "ChatModel")).thenReturn(1);
        when(inboxKeywordMapper.insertKeyword(1L, "Tool Calling")).thenReturn(1);
        when(inboxEntityMapper.insertEntity(1L, "Spring AI", "TECHNOLOGY")).thenReturn(1);
        when(inboxEntityMapper.insertEntity(1L, "OpenAI", "ORGANIZATION")).thenReturn(1);
        InboxItem updated = new InboxItem();
        updated.setId(1L);
        updated.setSummary("摘要");
        updated.setCategory("技术学习");
        when(inboxItemMapper.selectById(1L)).thenReturn(updated);
        when(inboxTagMapper.selectTagNamesByInboxItemId(1L))
                .thenReturn(List.of("Java", "Spring AI"));
        when(inboxKeywordMapper.selectKeywordsByInboxItemId(1L))
                .thenReturn(List.of("ChatModel", "Tool Calling"));
        when(inboxEntityMapper.selectEntitiesByInboxItemId(1L)).thenReturn(List.of(
                entity("Spring AI", "TECHNOLOGY"),
                entity("OpenAI", "ORGANIZATION")
        ));

        InboxItem result = persistenceService.replaceAnalysis(
                1L,
                "摘要",
                "技术学习",
                tags,
                List.of("ChatModel", "Tool Calling"),
                List.of(
                        new NormalizedEntity("Spring AI", "TECHNOLOGY"),
                        new NormalizedEntity("OpenAI", "ORGANIZATION")
                )
        );

        assertEquals(List.of("Java", "Spring AI"), result.getTags());
        assertEquals(List.of("ChatModel", "Tool Calling"), result.getKeywords());
        assertEquals(List.of(
                new AiEntityResponse("Spring AI", "TECHNOLOGY"),
                new AiEntityResponse("OpenAI", "ORGANIZATION")
        ), result.getEntities());
        InOrder order = inOrder(
                inboxItemMapper,
                inboxTagMapper,
                tagMapper,
                inboxKeywordMapper,
                inboxEntityMapper
        );
        order.verify(inboxItemMapper).updateAnalysis(1L, "摘要", "技术学习");
        order.verify(inboxTagMapper).deleteByInboxItemId(1L);
        order.verify(tagMapper).upsertTag("Java", "java");
        order.verify(tagMapper).selectIdByNormalizedName("java");
        order.verify(inboxTagMapper).insertRelation(1L, 10L);
        order.verify(tagMapper).upsertTag("Spring AI", "spring ai");
        order.verify(tagMapper).selectIdByNormalizedName("spring ai");
        order.verify(inboxTagMapper).insertRelation(1L, 11L);
        order.verify(inboxKeywordMapper).deleteByInboxItemId(1L);
        order.verify(inboxKeywordMapper).insertKeyword(1L, "ChatModel");
        order.verify(inboxKeywordMapper).insertKeyword(1L, "Tool Calling");
        order.verify(inboxEntityMapper).deleteByInboxItemId(1L);
        order.verify(inboxEntityMapper).insertEntity(1L, "Spring AI", "TECHNOLOGY");
        order.verify(inboxEntityMapper).insertEntity(1L, "OpenAI", "ORGANIZATION");
    }

    @Test
    void missingItemStopsBeforeDeletingOldRelations() {
        when(inboxItemMapper.updateAnalysis(99L, "摘要", "其他")).thenReturn(0);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> persistenceService.replaceAnalysis(
                        99L,
                        "摘要",
                        "其他",
                        List.of(new NormalizedTag("测试", "测试")),
                        List.of("关键词"),
                        List.of(new NormalizedEntity("实体", "OTHER"))
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(inboxTagMapper, never()).deleteByInboxItemId(anyLong());
        verify(inboxKeywordMapper, never()).deleteByInboxItemId(anyLong());
        verify(inboxEntityMapper, never()).deleteByInboxItemId(anyLong());
    }

    @Test
    void tagFailureEscapesTransactionAndDoesNotContinueWithRelations() {
        when(inboxItemMapper.updateAnalysis(1L, "新摘要", "工作")).thenReturn(1);
        when(tagMapper.upsertTag("Java", "java"))
                .thenThrow(new IllegalStateException("mock tag failure"));

        assertThrows(
                IllegalStateException.class,
                () -> persistenceService.replaceAnalysis(
                        1L,
                        "新摘要",
                        "工作",
                        List.of(new NormalizedTag("Java", "java")),
                        List.of("并发"),
                        List.of(new NormalizedEntity("Java", "TECHNOLOGY"))
                )
        );

        verify(inboxTagMapper).deleteByInboxItemId(1L);
        verify(inboxTagMapper, never()).insertRelation(anyLong(), anyLong());
        verify(inboxKeywordMapper, never()).deleteByInboxItemId(anyLong());
        verify(inboxItemMapper, never()).selectById(anyLong());
    }

    @Test
    void entityFailureEscapesAfterEarlierResultsAndSkipsReload() {
        when(inboxItemMapper.updateAnalysis(1L, "新摘要", "技术学习")).thenReturn(1);
        when(tagMapper.selectIdByNormalizedName("java")).thenReturn(10L);
        when(inboxTagMapper.insertRelation(1L, 10L)).thenReturn(1);
        when(inboxKeywordMapper.insertKeyword(1L, "ChatModel")).thenReturn(1);
        when(inboxEntityMapper.insertEntity(1L, "Spring AI", "TECHNOLOGY")).thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> persistenceService.replaceAnalysis(
                        1L,
                        "新摘要",
                        "技术学习",
                        List.of(new NormalizedTag("Java", "java")),
                        List.of("ChatModel"),
                        List.of(new NormalizedEntity("Spring AI", "TECHNOLOGY"))
                )
        );

        verify(inboxTagMapper).insertRelation(1L, 10L);
        verify(inboxKeywordMapper).insertKeyword(1L, "ChatModel");
        verify(inboxEntityMapper).deleteByInboxItemId(1L);
        verify(inboxItemMapper, never()).selectById(anyLong());
    }

    @Test
    void replaceAnalysisDefinesSpringTransactionBoundary() throws NoSuchMethodException {
        Method method = InboxAnalysisPersistenceService.class.getMethod(
                "replaceAnalysis",
                Long.class,
                String.class,
                String.class,
                List.class,
                List.class,
                List.class
        );

        assertTrue(method.isAnnotationPresent(Transactional.class));
    }

    private InboxEntity entity(String name, String type) {
        InboxEntity entity = new InboxEntity();
        entity.setName(name);
        entity.setType(type);
        return entity;
    }
}

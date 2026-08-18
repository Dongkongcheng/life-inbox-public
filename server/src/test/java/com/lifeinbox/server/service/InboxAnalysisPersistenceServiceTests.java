package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
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
    private final InboxAnalysisPersistenceService persistenceService =
            new InboxAnalysisPersistenceService(inboxItemMapper, tagMapper, inboxTagMapper);

    @Test
    void completeAnalysisReplacesOldRelationsAndReturnsTags() {
        List<NormalizedTag> tags = List.of(
                new NormalizedTag("Java", "java"),
                new NormalizedTag("Spring AI", "spring ai")
        );
        when(inboxItemMapper.updateAnalysis(1L, "摘要", "技术学习")).thenReturn(1);
        when(tagMapper.selectIdByNormalizedName("java")).thenReturn(10L);
        when(tagMapper.selectIdByNormalizedName("spring ai")).thenReturn(11L);
        when(inboxTagMapper.insertRelation(1L, 10L)).thenReturn(1);
        when(inboxTagMapper.insertRelation(1L, 11L)).thenReturn(1);
        InboxItem updated = new InboxItem();
        updated.setId(1L);
        updated.setSummary("摘要");
        updated.setCategory("技术学习");
        when(inboxItemMapper.selectById(1L)).thenReturn(updated);
        when(inboxTagMapper.selectTagNamesByInboxItemId(1L))
                .thenReturn(List.of("Java", "Spring AI"));

        InboxItem result = persistenceService.replaceAnalysis(1L, "摘要", "技术学习", tags);

        assertEquals(List.of("Java", "Spring AI"), result.getTags());
        InOrder order = inOrder(inboxItemMapper, inboxTagMapper, tagMapper);
        order.verify(inboxItemMapper).updateAnalysis(1L, "摘要", "技术学习");
        order.verify(inboxTagMapper).deleteByInboxItemId(1L);
        order.verify(tagMapper).upsertTag("Java", "java");
        order.verify(tagMapper).selectIdByNormalizedName("java");
        order.verify(inboxTagMapper).insertRelation(1L, 10L);
        order.verify(tagMapper).upsertTag("Spring AI", "spring ai");
        order.verify(tagMapper).selectIdByNormalizedName("spring ai");
        order.verify(inboxTagMapper).insertRelation(1L, 11L);
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
                        List.of(new NormalizedTag("测试", "测试"))
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(inboxTagMapper, never()).deleteByInboxItemId(anyLong());
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
                        List.of(new NormalizedTag("Java", "java"))
                )
        );

        verify(inboxTagMapper).deleteByInboxItemId(1L);
        verify(inboxTagMapper, never()).insertRelation(anyLong(), anyLong());
        verify(inboxItemMapper, never()).selectById(anyLong());
    }

    @Test
    void replaceAnalysisDefinesSpringTransactionBoundary() throws NoSuchMethodException {
        Method method = InboxAnalysisPersistenceService.class.getMethod(
                "replaceAnalysis",
                Long.class,
                String.class,
                String.class,
                List.class
        );

        assertTrue(method.isAnnotationPresent(Transactional.class));
    }
}

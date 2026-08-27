package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.ContentRelation;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.RelationType;
import com.lifeinbox.server.mapper.ContentRelationMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentRelationServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final ContentRelationMapper contentRelationMapper = mock(ContentRelationMapper.class);
    private final ContentRelationService service = new ContentRelationService(
            inboxItemMapper,
            contentRelationMapper
    );

    @Test
    void activeEndpointsCreateRelatedToUsingCanonicalOrdering() {
        stubActiveEndpoints(10L, 20L);
        ContentRelation saved = relation(1L, 10L, 20L);
        when(contentRelationMapper.selectCanonicalPair(10L, 20L, RelationType.RELATED_TO))
                .thenReturn(null, saved);
        when(contentRelationMapper.insert(any(ContentRelation.class))).thenReturn(1);

        ContentRelation result = service.ensureRelatedTo(20L, 10L);

        assertSame(saved, result);
        ArgumentCaptor<ContentRelation> inserted = ArgumentCaptor.forClass(ContentRelation.class);
        verify(contentRelationMapper).insert(inserted.capture());
        assertEquals(10L, inserted.getValue().getLeftInboxItemId());
        assertEquals(20L, inserted.getValue().getRightInboxItemId());
        assertEquals(RelationType.RELATED_TO, inserted.getValue().getRelationType());
        verify(inboxItemMapper).selectRelationEndpointsForUpdate(10L, 20L);
    }

    @Test
    void repeatedSamePairIsIdempotent() {
        stubActiveEndpoints(10L, 20L);
        ContentRelation saved = relation(2L, 10L, 20L);
        when(contentRelationMapper.selectCanonicalPair(10L, 20L, RelationType.RELATED_TO))
                .thenReturn(null, saved, saved);
        when(contentRelationMapper.insert(any(ContentRelation.class))).thenReturn(1);

        assertSame(saved, service.ensureRelatedTo(10L, 20L));
        assertSame(saved, service.ensureRelatedTo(10L, 20L));

        verify(contentRelationMapper, times(1)).insert(any(ContentRelation.class));
    }

    @Test
    void reversePairReusesTheSameCanonicalRelation() {
        stubActiveEndpoints(10L, 20L);
        ContentRelation saved = relation(3L, 10L, 20L);
        when(contentRelationMapper.selectCanonicalPair(10L, 20L, RelationType.RELATED_TO))
                .thenReturn(null, saved, saved);
        when(contentRelationMapper.insert(any(ContentRelation.class))).thenReturn(1);

        assertSame(saved, service.ensureRelatedTo(10L, 20L));
        assertSame(saved, service.ensureRelatedTo(20L, 10L));

        verify(contentRelationMapper, times(1)).insert(any(ContentRelation.class));
        verify(inboxItemMapper, times(2)).selectRelationEndpointsForUpdate(10L, 20L);
    }

    @Test
    void selfRelationIsRejectedBeforeEndpointLookup() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ensureRelatedTo(10L, 10L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(inboxItemMapper, never()).selectRelationEndpointsForUpdate(10L, 10L);
        verify(contentRelationMapper, never()).insert(any(ContentRelation.class));
    }

    @Test
    void missingEndpointIsRejected() {
        when(inboxItemMapper.selectRelationEndpointsForUpdate(10L, 20L))
                .thenReturn(List.of(item(10L, "ACTIVE")));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ensureRelatedTo(10L, 20L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(contentRelationMapper, never()).insert(any(ContentRelation.class));
    }

    @Test
    void archivedEndpointCannotCreateOrReuseThroughTheWritePath() {
        when(inboxItemMapper.selectRelationEndpointsForUpdate(10L, 20L))
                .thenReturn(List.of(item(10L, "ACTIVE"), item(20L, "ARCHIVED")));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ensureRelatedTo(10L, 20L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(contentRelationMapper, never()).selectCanonicalPair(
                10L,
                20L,
                RelationType.RELATED_TO
        );
        verify(contentRelationMapper, never()).insert(any(ContentRelation.class));
    }

    @Test
    void databaseDuplicateIsReloadedAsAnIdempotentConcurrentResult() {
        stubActiveEndpoints(10L, 20L);
        ContentRelation concurrent = relation(4L, 10L, 20L);
        when(contentRelationMapper.selectCanonicalPair(10L, 20L, RelationType.RELATED_TO))
                .thenReturn(null, concurrent);
        when(contentRelationMapper.insert(any(ContentRelation.class)))
                .thenThrow(new DuplicateKeyException("mock unique constraint"));

        assertSame(concurrent, service.ensureRelatedTo(20L, 10L));
    }

    @Test
    void archivedExistingRelationRemainsAvailableThroughMinimalQuery() {
        ContentRelation existing = relation(5L, 10L, 20L);
        when(contentRelationMapper.selectByInboxItemId(20L)).thenReturn(List.of(existing));

        assertEquals(List.of(existing), service.findByInboxItemId(20L));

        verify(contentRelationMapper).selectByInboxItemId(20L);
        verify(inboxItemMapper, never()).selectById(20L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void invalidEndpointIdIsRejected(Long inboxItemId) {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ensureRelatedTo(inboxItemId, 20L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(contentRelationMapper, never()).insert(any(ContentRelation.class));
    }

    private void stubActiveEndpoints(Long leftInboxItemId, Long rightInboxItemId) {
        when(inboxItemMapper.selectRelationEndpointsForUpdate(
                leftInboxItemId,
                rightInboxItemId
        )).thenReturn(List.of(
                item(leftInboxItemId, "ACTIVE"),
                item(rightInboxItemId, "ACTIVE")
        ));
    }

    private InboxItem item(Long id, String status) {
        InboxItem item = new InboxItem();
        item.setId(id);
        item.setStatus(status);
        return item;
    }

    private ContentRelation relation(Long id, Long leftInboxItemId, Long rightInboxItemId) {
        ContentRelation relation = new ContentRelation();
        relation.setId(id);
        relation.setLeftInboxItemId(leftInboxItemId);
        relation.setRightInboxItemId(rightInboxItemId);
        relation.setRelationType(RelationType.RELATED_TO);
        return relation;
    }
}

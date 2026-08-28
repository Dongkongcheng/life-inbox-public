package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.ContentRelation;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.RelationType;
import com.lifeinbox.server.dto.RelationPersistenceResult;
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

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

    @Test
    void batchPersistsMultipleRelationsWithOneEndpointAndExistingRelationQuery() {
        when(inboxItemMapper.selectRelationEndpointsForUpdateByIds(
                List.of(10L, 20L, 30L)
        )).thenReturn(List.of(
                item(10L, "ACTIVE"),
                item(20L, "ACTIVE"),
                item(30L, "ACTIVE")
        ));
        when(contentRelationMapper.selectByInboxItemId(20L)).thenReturn(List.of());
        when(contentRelationMapper.insert(any(ContentRelation.class))).thenReturn(1);

        RelationPersistenceResult result = service.ensureRelatedToBatch(
                20L,
                List.of(10L, 30L)
        );

        assertEquals(2, result.discoveredCount());
        assertEquals(2, result.persistedNewCount());
        assertEquals(0, result.alreadyExistingCount());
        assertEquals(0, result.skippedInvalidCount());
        assertEquals(2, result.relations().size());
        ArgumentCaptor<ContentRelation> inserted = ArgumentCaptor.forClass(
                ContentRelation.class
        );
        verify(contentRelationMapper, times(2)).insert(inserted.capture());
        assertEquals(
                List.of("10-20", "20-30"),
                inserted.getAllValues().stream()
                        .map(relation -> relation.getLeftInboxItemId()
                                + "-" + relation.getRightInboxItemId())
                        .toList()
        );
        verify(inboxItemMapper, times(1)).selectRelationEndpointsForUpdateByIds(
                List.of(10L, 20L, 30L)
        );
        verify(contentRelationMapper, times(1)).selectByInboxItemId(20L);
        verify(contentRelationMapper, never()).selectCanonicalPair(
                any(),
                any(),
                any()
        );
    }

    @Test
    void batchReusesReverseExistingRelationAndPersistsOnlyNewTarget() {
        when(inboxItemMapper.selectRelationEndpointsForUpdateByIds(
                List.of(10L, 20L, 30L)
        )).thenReturn(List.of(
                item(10L, "ACTIVE"),
                item(20L, "ACTIVE"),
                item(30L, "ACTIVE")
        ));
        ContentRelation existing = relation(5L, 10L, 20L);
        when(contentRelationMapper.selectByInboxItemId(20L)).thenReturn(
                List.of(existing)
        );
        when(contentRelationMapper.insert(any(ContentRelation.class))).thenReturn(1);

        RelationPersistenceResult result = service.ensureRelatedToBatch(
                20L,
                List.of(10L, 30L)
        );

        assertEquals(1, result.persistedNewCount());
        assertEquals(1, result.alreadyExistingCount());
        assertEquals(List.of(existing, result.relations().get(1)), result.relations());
        ArgumentCaptor<ContentRelation> inserted = ArgumentCaptor.forClass(
                ContentRelation.class
        );
        verify(contentRelationMapper).insert(inserted.capture());
        assertEquals(20L, inserted.getValue().getLeftInboxItemId());
        assertEquals(30L, inserted.getValue().getRightInboxItemId());
    }

    @Test
    void batchResultCountsNewExistingAndSkippedTargetsSeparately() {
        when(inboxItemMapper.selectRelationEndpointsForUpdateByIds(
                List.of(10L, 20L, 30L, 40L, 50L)
        )).thenReturn(List.of(
                item(10L, "ACTIVE"),
                item(20L, "ACTIVE"),
                item(30L, "ACTIVE"),
                item(40L, "ARCHIVED"),
                item(50L, "ACTIVE")
        ));
        ContentRelation existing = relation(5L, 10L, 20L);
        when(contentRelationMapper.selectByInboxItemId(20L)).thenReturn(
                List.of(existing)
        );
        when(contentRelationMapper.insert(any(ContentRelation.class))).thenReturn(1);

        RelationPersistenceResult result = service.ensureRelatedToBatch(
                20L,
                List.of(10L, 30L, 40L, 50L)
        );

        assertEquals(4, result.discoveredCount());
        assertEquals(2, result.persistedNewCount());
        assertEquals(1, result.alreadyExistingCount());
        assertEquals(1, result.skippedInvalidCount());
        assertEquals(3, result.relations().size());
    }

    @Test
    void additiveBatchNeverRemovesRelationsMissingFromCurrentDiscovery() {
        when(inboxItemMapper.selectRelationEndpointsForUpdateByIds(
                List.of(20L, 40L)
        )).thenReturn(List.of(item(20L, "ACTIVE"), item(40L, "ACTIVE")));
        ContentRelation oldFirst = relation(1L, 10L, 20L);
        ContentRelation oldSecond = relation(2L, 20L, 30L);
        when(contentRelationMapper.selectByInboxItemId(20L)).thenReturn(
                List.of(oldFirst, oldSecond)
        );
        when(contentRelationMapper.insert(any(ContentRelation.class))).thenReturn(1);

        RelationPersistenceResult result = service.ensureRelatedToBatch(
                20L,
                List.of(40L)
        );

        assertEquals(1, result.persistedNewCount());
        verify(contentRelationMapper).selectByInboxItemId(20L);
        verify(contentRelationMapper).insert(any(ContentRelation.class));
        verifyNoMoreInteractions(contentRelationMapper);
    }

    @Test
    void batchSkipsSelfDuplicateMissingArchivedAndInvalidTargets() {
        List<Long> suggestions = Arrays.asList(20L, null, -1L, 10L, 10L, 30L, 40L);
        when(inboxItemMapper.selectRelationEndpointsForUpdateByIds(
                List.of(10L, 20L, 30L, 40L)
        )).thenReturn(List.of(
                item(10L, "ARCHIVED"),
                item(20L, "ACTIVE"),
                item(30L, "ACTIVE")
        ));
        when(contentRelationMapper.selectByInboxItemId(20L)).thenReturn(List.of());
        when(contentRelationMapper.insert(any(ContentRelation.class))).thenReturn(1);

        RelationPersistenceResult result = service.ensureRelatedToBatch(20L, suggestions);

        assertEquals(7, result.discoveredCount());
        assertEquals(1, result.persistedNewCount());
        assertEquals(0, result.alreadyExistingCount());
        assertEquals(6, result.skippedInvalidCount());
        ArgumentCaptor<ContentRelation> inserted = ArgumentCaptor.forClass(
                ContentRelation.class
        );
        verify(contentRelationMapper).insert(inserted.capture());
        assertEquals(20L, inserted.getValue().getLeftInboxItemId());
        assertEquals(30L, inserted.getValue().getRightInboxItemId());
    }

    @Test
    void batchStopsWhenSourceWasDeletedBeforePersistence() {
        when(inboxItemMapper.selectRelationEndpointsForUpdateByIds(
                List.of(10L, 20L)
        )).thenReturn(List.of(item(10L, "ACTIVE")));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ensureRelatedToBatch(20L, List.of(10L))
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(contentRelationMapper, never()).selectByInboxItemId(any());
        verify(contentRelationMapper, never()).insert(any(ContentRelation.class));
    }

    @Test
    void batchStopsWhenSourceWasArchivedBeforePersistence() {
        when(inboxItemMapper.selectRelationEndpointsForUpdateByIds(
                List.of(10L, 20L)
        )).thenReturn(List.of(
                item(10L, "ACTIVE"),
                item(20L, "ARCHIVED")
        ));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ensureRelatedToBatch(20L, List.of(10L))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(contentRelationMapper, never()).selectByInboxItemId(any());
        verify(contentRelationMapper, never()).insert(any(ContentRelation.class));
    }

    @Test
    void emptyBatchValidatesSourceAndLeavesExistingRelationsUntouched() {
        when(inboxItemMapper.selectRelationEndpointsForUpdateByIds(List.of(20L)))
                .thenReturn(List.of(item(20L, "ACTIVE")));

        RelationPersistenceResult result = service.ensureRelatedToBatch(20L, List.of());

        assertEquals(0, result.discoveredCount());
        assertEquals(0, result.persistedNewCount());
        assertEquals(0, result.alreadyExistingCount());
        assertEquals(0, result.skippedInvalidCount());
        assertEquals(List.of(), result.relations());
        verify(contentRelationMapper, never()).selectByInboxItemId(any());
        verify(contentRelationMapper, never()).insert(any(ContentRelation.class));
    }

    @Test
    void batchDuplicateKeyRaceIsAnIdempotentExistingResult() {
        when(inboxItemMapper.selectRelationEndpointsForUpdateByIds(
                List.of(10L, 20L)
        )).thenReturn(List.of(item(10L, "ACTIVE"), item(20L, "ACTIVE")));
        when(contentRelationMapper.selectByInboxItemId(20L)).thenReturn(List.of());
        ContentRelation concurrent = relation(8L, 10L, 20L);
        when(contentRelationMapper.insert(any(ContentRelation.class))).thenThrow(
                new DuplicateKeyException("mock concurrent unique key")
        );
        when(contentRelationMapper.selectCanonicalPair(
                10L,
                20L,
                RelationType.RELATED_TO
        )).thenReturn(concurrent);

        RelationPersistenceResult result = service.ensureRelatedToBatch(
                20L,
                List.of(10L)
        );

        assertEquals(0, result.persistedNewCount());
        assertEquals(1, result.alreadyExistingCount());
        assertSame(concurrent, result.relations().getFirst());
    }

    @Test
    void unexpectedBatchInsertFailureIsNotDowngradedToSkippedInvalid() {
        when(inboxItemMapper.selectRelationEndpointsForUpdateByIds(
                List.of(10L, 20L)
        )).thenReturn(List.of(item(10L, "ACTIVE"), item(20L, "ACTIVE")));
        when(contentRelationMapper.selectByInboxItemId(20L)).thenReturn(List.of());
        when(contentRelationMapper.insert(any(ContentRelation.class))).thenReturn(0);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ensureRelatedToBatch(20L, List.of(10L))
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
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

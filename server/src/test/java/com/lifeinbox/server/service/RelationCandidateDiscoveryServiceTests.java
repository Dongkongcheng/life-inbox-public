package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.AiVectorNeighborCandidate;
import com.lifeinbox.server.dto.AiVectorNeighborResponse;
import com.lifeinbox.server.dto.RelationDiscoveryCandidate;
import com.lifeinbox.server.entity.ContentRelation;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.RelationType;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RelationCandidateDiscoveryServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final ContentRelationService contentRelationService = mock(ContentRelationService.class);
    private final AiServiceClient aiServiceClient = mock(AiServiceClient.class);
    private final RelationCandidateDiscoveryService service =
            new RelationCandidateDiscoveryService(
                    inboxItemMapper,
                    contentRelationService,
                    aiServiceClient
            );

    @Test
    void validNeighborsAreBatchResolvedAndReturnedAsRuntimeCandidates() {
        stubActiveSource(123L);
        when(aiServiceClient.findVectorNeighbors(123L, 20)).thenReturn(neighbors(
                candidate(456L, 0.91),
                candidate(789L, 0.84)
        ));
        when(inboxItemMapper.selectActiveByIdsAndFilters(
                List.of(456L, 789L), null, null, null
        )).thenReturn(List.of(item(789L, "ACTIVE"), item(456L, "ACTIVE")));
        when(contentRelationService.findByInboxItemId(123L)).thenReturn(List.of());

        assertEquals(
                List.of(
                        new RelationDiscoveryCandidate(456L, 0.91),
                        new RelationDiscoveryCandidate(789L, 0.84)
                ),
                service.discoverCandidates(123L, null)
        );
        verify(inboxItemMapper).selectActiveByIdsAndFilters(
                List.of(456L, 789L), null, null, null
        );
    }

    @Test
    void missingSourceIsRejectedBeforeVectorLookup() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.discoverCandidates(123L, 20)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(aiServiceClient, never()).findVectorNeighbors(any(), any(Integer.class));
    }

    @Test
    void archivedSourceIsRejectedBeforeVectorLookup() {
        when(inboxItemMapper.selectById(123L)).thenReturn(item(123L, "ARCHIVED"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.discoverCandidates(123L, 20)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(aiServiceClient, never()).findVectorNeighbors(any(), any(Integer.class));
    }

    @Test
    void sourceNotIndexedIsNormalEmptyResult() {
        stubActiveSource(123L);
        when(aiServiceClient.findVectorNeighbors(123L, 20)).thenReturn(
                new AiVectorNeighborResponse(false, List.of())
        );

        assertEquals(List.of(), service.discoverCandidates(123L, 20));
        verify(inboxItemMapper, never()).selectActiveByIdsAndFilters(
                any(), any(), any(), any()
        );
        verify(contentRelationService, never()).findByInboxItemId(123L);
    }

    @Test
    void staleQdrantIdIsRemovedByMysqlAuthority() {
        stubCandidateResolution(
                123L,
                20,
                List.of(candidate(999L, 0.95)),
                List.of(),
                List.of()
        );

        assertEquals(List.of(), service.discoverCandidates(123L, 20));
    }

    @Test
    void archivedCandidateIsRejectedEvenIfMapperUnexpectedlyReturnsIt() {
        stubCandidateResolution(
                123L,
                20,
                List.of(candidate(456L, 0.95)),
                List.of(item(456L, "ARCHIVED")),
                List.of()
        );

        assertEquals(List.of(), service.discoverCandidates(123L, 20));
    }

    @Test
    void sourceIdIsFilteredAgainByJava() {
        stubActiveSource(123L);
        when(aiServiceClient.findVectorNeighbors(123L, 20)).thenReturn(neighbors(
                candidate(123L, 1.0),
                candidate(456L, 0.9)
        ));
        when(inboxItemMapper.selectActiveByIdsAndFilters(
                List.of(456L), null, null, null
        )).thenReturn(List.of(item(456L, "ACTIVE")));
        when(contentRelationService.findByInboxItemId(123L)).thenReturn(List.of());

        assertEquals(
                List.of(new RelationDiscoveryCandidate(456L, 0.9)),
                service.discoverCandidates(123L, 20)
        );
    }

    @Test
    void alreadyRelatedCandidatesAreFilteredFromEitherCanonicalSide() {
        stubCandidateResolution(
                123L,
                20,
                List.of(candidate(100L, 0.95), candidate(456L, 0.9), candidate(789L, 0.8)),
                List.of(
                        item(100L, "ACTIVE"),
                        item(456L, "ACTIVE"),
                        item(789L, "ACTIVE")
                ),
                List.of(relation(100L, 123L), relation(123L, 456L))
        );

        assertEquals(
                List.of(new RelationDiscoveryCandidate(789L, 0.8)),
                service.discoverCandidates(123L, 20)
        );
        verify(contentRelationService).findByInboxItemId(123L);
    }

    @Test
    void finalLimitIsAppliedAfterCandidatesAreResolved() {
        stubCandidateResolution(
                123L,
                2,
                List.of(
                        candidate(456L, 0.9),
                        candidate(789L, 0.8),
                        candidate(900L, 0.7)
                ),
                List.of(
                        item(456L, "ACTIVE"),
                        item(789L, "ACTIVE"),
                        item(900L, "ACTIVE")
                ),
                List.of()
        );

        assertEquals(
                List.of(
                        new RelationDiscoveryCandidate(456L, 0.9),
                        new RelationDiscoveryCandidate(789L, 0.8)
                ),
                service.discoverCandidates(123L, 2)
        );
        verify(aiServiceClient).findVectorNeighbors(123L, 2);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 21})
    void invalidLimitIsRejectedBeforeSourceOrVectorLookup(int limit) {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.discoverCandidates(123L, limit)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(inboxItemMapper, never()).selectById(any());
        verify(aiServiceClient, never()).findVectorNeighbors(any(), any(Integer.class));
    }

    @Test
    void rankingIsScoreDescendingThenIdAscendingForTies() {
        stubCandidateResolution(
                123L,
                20,
                List.of(
                        candidate(900L, 0.7),
                        candidate(789L, 0.9),
                        candidate(456L, 0.9)
                ),
                List.of(
                        item(900L, "ACTIVE"),
                        item(789L, "ACTIVE"),
                        item(456L, "ACTIVE")
                ),
                List.of()
        );

        assertEquals(
                List.of(456L, 789L, 900L),
                service.discoverCandidates(123L, 20).stream()
                        .map(RelationDiscoveryCandidate::inboxItemId)
                        .toList()
        );
    }

    @Test
    void filteringHappensBeforeFinalLimit() {
        stubCandidateResolution(
                123L,
                2,
                List.of(
                        candidate(400L, 0.99),
                        candidate(500L, 0.98),
                        candidate(600L, 0.97),
                        candidate(700L, 0.96)
                ),
                List.of(
                        item(400L, "ARCHIVED"),
                        item(500L, "ACTIVE"),
                        item(600L, "ACTIVE"),
                        item(700L, "ACTIVE")
                ),
                List.of(relation(123L, 500L))
        );

        assertEquals(
                List.of(600L, 700L),
                service.discoverCandidates(123L, 2).stream()
                        .map(RelationDiscoveryCandidate::inboxItemId)
                        .toList()
        );
    }

    @Test
    void vectorInfrastructureFailurePropagatesWithoutMysqlCandidateQuery() {
        stubActiveSource(123L);
        when(aiServiceClient.findVectorNeighbors(123L, 20)).thenThrow(
                new AiServiceUnavailableException("Vector unavailable")
        );

        assertThrows(
                AiServiceUnavailableException.class,
                () -> service.discoverCandidates(123L, 20)
        );
        verify(inboxItemMapper, never()).selectActiveByIdsAndFilters(
                any(), any(), any(), any()
        );
    }

    @Test
    void discoveryNeverPersistsAFormalRelation() {
        stubCandidateResolution(
                123L,
                20,
                List.of(candidate(456L, 0.9)),
                List.of(item(456L, "ACTIVE")),
                List.of()
        );

        service.discoverCandidates(123L, 20);

        verify(contentRelationService, never()).ensureRelatedTo(any(), any());
    }

    @Test
    void backfillReadinessProbeOnlyReadsExistingVectorState() {
        when(aiServiceClient.findVectorNeighbors(321L, 1)).thenReturn(
                new AiVectorNeighborResponse(true, List.of())
        );

        assertEquals(true, service.isSourceVectorReady(321L));

        verify(aiServiceClient).findVectorNeighbors(321L, 1);
        verify(aiServiceClient, never()).indexVector(any(), any());
        verifyNoInteractions(inboxItemMapper, contentRelationService);
    }

    private void stubActiveSource(Long sourceInboxItemId) {
        when(inboxItemMapper.selectById(sourceInboxItemId)).thenReturn(
                item(sourceInboxItemId, "ACTIVE")
        );
    }

    private void stubCandidateResolution(
            Long sourceInboxItemId,
            int limit,
            List<AiVectorNeighborCandidate> candidates,
            List<InboxItem> resolvedItems,
            List<ContentRelation> relations
    ) {
        stubActiveSource(sourceInboxItemId);
        when(aiServiceClient.findVectorNeighbors(sourceInboxItemId, limit)).thenReturn(
                new AiVectorNeighborResponse(true, candidates)
        );
        List<Long> orderedIds = candidates.stream()
                .filter(candidate -> !sourceInboxItemId.equals(candidate.inboxItemId()))
                .sorted((first, second) -> {
                    int scoreOrder = Double.compare(second.score(), first.score());
                    return scoreOrder != 0
                            ? scoreOrder
                            : Long.compare(first.inboxItemId(), second.inboxItemId());
                })
                .map(AiVectorNeighborCandidate::inboxItemId)
                .distinct()
                .toList();
        when(inboxItemMapper.selectActiveByIdsAndFilters(
                orderedIds, null, null, null
        )).thenReturn(resolvedItems);
        when(contentRelationService.findByInboxItemId(sourceInboxItemId)).thenReturn(relations);
    }

    private AiVectorNeighborResponse neighbors(AiVectorNeighborCandidate... candidates) {
        return new AiVectorNeighborResponse(true, List.of(candidates));
    }

    private AiVectorNeighborCandidate candidate(Long inboxItemId, double score) {
        return new AiVectorNeighborCandidate(inboxItemId, score);
    }

    private InboxItem item(Long id, String status) {
        InboxItem item = new InboxItem();
        item.setId(id);
        item.setStatus(status);
        return item;
    }

    private ContentRelation relation(Long leftInboxItemId, Long rightInboxItemId) {
        ContentRelation relation = new ContentRelation();
        relation.setLeftInboxItemId(leftInboxItemId);
        relation.setRightInboxItemId(rightInboxItemId);
        relation.setRelationType(RelationType.RELATED_TO);
        return relation;
    }
}

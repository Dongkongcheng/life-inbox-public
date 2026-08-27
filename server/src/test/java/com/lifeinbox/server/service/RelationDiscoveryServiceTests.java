package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.AiRelationDiscoveryItem;
import com.lifeinbox.server.dto.AiRelationDiscoveryResponse;
import com.lifeinbox.server.dto.RelationDiscoveryCandidate;
import com.lifeinbox.server.dto.RelationDiscoverySuggestion;
import com.lifeinbox.server.entity.ContentRelation;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.RelationType;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationDiscoveryServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final ContentRelationService contentRelationService = mock(ContentRelationService.class);
    private final RelationCandidateDiscoveryService candidateDiscoveryService =
            mock(RelationCandidateDiscoveryService.class);
    private final AiServiceClient aiServiceClient = mock(AiServiceClient.class);
    private final RelationDiscoveryTextBuilder textBuilder = new RelationDiscoveryTextBuilder(
            new InboxSearchableContentService(mock(InboxItemMapper.class))
    );
    private final RelationDiscoveryService service = new RelationDiscoveryService(
            inboxItemMapper,
            contentRelationService,
            candidateDiscoveryService,
            textBuilder,
            aiServiceClient
    );

    @Test
    void validCandidatesAreRevalidatedAndSentInOneBatchWithoutSemanticScores() {
        stubDiscovery(
                List.of(
                        new RelationDiscoveryCandidate(456L, 0.98),
                        new RelationDiscoveryCandidate(789L, 0.83)
                ),
                List.of(
                        textItem(789L, "候选二", "与事务传播相关"),
                        textItem(456L, "候选一", "同一事务失效案例")
                ),
                List.of()
        );
        when(aiServiceClient.discoverRelations(any(), any())).thenReturn(
                new AiRelationDiscoveryResponse(List.of(456L))
        );

        List<RelationDiscoverySuggestion> result = service.discoverRelations(123L, null);

        assertEquals(
                List.of(new RelationDiscoverySuggestion(123L, 456L, RelationType.RELATED_TO)),
                result
        );
        verify(candidateDiscoveryService).discoverCandidates(123L, 20);
        verify(inboxItemMapper).selectActiveByIdsAndFilters(
                List.of(456L, 789L), null, null, null
        );
        ArgumentCaptor<AiRelationDiscoveryItem> sourceCaptor = ArgumentCaptor.forClass(
                AiRelationDiscoveryItem.class
        );
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiRelationDiscoveryItem>> candidatesCaptor = ArgumentCaptor.forClass(
                List.class
        );
        verify(aiServiceClient, times(1)).discoverRelations(
                sourceCaptor.capture(),
                candidatesCaptor.capture()
        );
        assertEquals(123L, sourceCaptor.getValue().inboxItemId());
        assertEquals(List.of(456L, 789L), candidatesCaptor.getValue().stream()
                .map(AiRelationDiscoveryItem::inboxItemId)
                .toList());
        assertEquals("标题：候选一\n正文：同一事务失效案例", candidatesCaptor.getValue().getFirst().text());
        verify(contentRelationService, never()).ensureRelatedTo(any(), any());
    }

    @Test
    void noTask42CandidatesReturnsEmptyWithoutLlmCall() {
        InboxItem source = source();
        when(inboxItemMapper.selectById(123L)).thenReturn(source);
        when(candidateDiscoveryService.discoverCandidates(123L, 20)).thenReturn(List.of());

        assertEquals(List.of(), service.discoverRelations(123L, null));

        verify(aiServiceClient, never()).discoverRelations(any(), any());
        verify(inboxItemMapper, never()).selectActiveByIdsAndFilters(any(), any(), any(), any());
    }

    @Test
    void noLlmRelationsIsAValidEmptyResult() {
        stubDiscovery(
                List.of(new RelationDiscoveryCandidate(456L, 0.9)),
                List.of(textItem(456L, "候选", "不同问题")),
                List.of()
        );
        when(aiServiceClient.discoverRelations(any(), any())).thenReturn(
                new AiRelationDiscoveryResponse(List.of())
        );

        assertEquals(List.of(), service.discoverRelations(123L, 20));
        verify(aiServiceClient, times(1)).discoverRelations(any(), any());
    }

    @Test
    void missingOrArchivedSourceIsRejectedBeforeTask42() {
        ResponseStatusException missing = assertThrows(
                ResponseStatusException.class,
                () -> service.discoverRelations(123L, 20)
        );
        when(inboxItemMapper.selectById(123L)).thenReturn(item(123L, "TEXT", "ARCHIVED"));
        ResponseStatusException archived = assertThrows(
                ResponseStatusException.class,
                () -> service.discoverRelations(123L, 20)
        );

        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, archived.getStatusCode());
        verify(candidateDiscoveryService, never()).discoverCandidates(any(), anyInt());
    }

    @Test
    void sourceArchivedAfterTask42IsRejectedBeforeCandidateOrLlmCall() {
        when(inboxItemMapper.selectById(123L)).thenReturn(
                source(),
                item(123L, "TEXT", "ARCHIVED")
        );
        when(candidateDiscoveryService.discoverCandidates(123L, 20)).thenReturn(
                List.of(new RelationDiscoveryCandidate(456L, 0.9))
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.discoverRelations(123L, 20)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(aiServiceClient, never()).discoverRelations(any(), any());
    }

    @Test
    void archivedOrDeletedCandidatesAreRemovedByMysqlAuthority() {
        stubDiscovery(
                List.of(
                        new RelationDiscoveryCandidate(456L, 0.9),
                        new RelationDiscoveryCandidate(789L, 0.8)
                ),
                List.of(item(456L, "TEXT", "ARCHIVED")),
                List.of()
        );

        assertEquals(List.of(), service.discoverRelations(123L, 20));
        verify(aiServiceClient, never()).discoverRelations(any(), any());
    }

    @Test
    void relationCreatedAfterTask42FiltersTheCandidateAsARace() {
        stubDiscovery(
                List.of(new RelationDiscoveryCandidate(456L, 0.9)),
                List.of(textItem(456L, "候选", "已有关系")),
                List.of(relation(123L, 456L))
        );

        assertEquals(List.of(), service.discoverRelations(123L, 20));
        verify(aiServiceClient, never()).discoverRelations(any(), any());
    }

    @Test
    void sourceWithoutUsableTextSkipsTask42AndLlm() {
        InboxItem source = item(123L, "URL", "ACTIVE");
        when(inboxItemMapper.selectById(123L)).thenReturn(source);

        assertEquals(List.of(), service.discoverRelations(123L, 20));

        verify(candidateDiscoveryService, never()).discoverCandidates(any(), anyInt());
        verify(aiServiceClient, never()).discoverRelations(any(), any());
    }

    @Test
    void candidateWithoutUsableTextIsSkipped() {
        stubDiscovery(
                List.of(new RelationDiscoveryCandidate(456L, 0.9)),
                List.of(item(456L, "URL", "ACTIVE")),
                List.of()
        );

        assertEquals(List.of(), service.discoverRelations(123L, 20));
        verify(aiServiceClient, never()).discoverRelations(any(), any());
    }

    @Test
    void providerFailurePropagatesWithoutPersistingRelation() {
        stubDiscovery(
                List.of(new RelationDiscoveryCandidate(456L, 0.9)),
                List.of(textItem(456L, "候选", "正文")),
                List.of()
        );
        when(aiServiceClient.discoverRelations(any(), any())).thenThrow(
                new AiServiceUnavailableException("AI Relation Discovery 服务暂不可用")
        );

        assertThrows(
                AiServiceUnavailableException.class,
                () -> service.discoverRelations(123L, 20)
        );
        verify(contentRelationService, never()).ensureRelatedTo(any(), any());
    }

    @Test
    void malformedTask42CandidateIsRejectedBeforeMysqlOrLlm() {
        when(inboxItemMapper.selectById(123L)).thenReturn(source());
        when(candidateDiscoveryService.discoverCandidates(123L, 20)).thenReturn(
                List.of(new RelationDiscoveryCandidate(123L, 1.0))
        );

        assertThrows(
                AiServiceUnavailableException.class,
                () -> service.discoverRelations(123L, 20)
        );
        verify(inboxItemMapper, never()).selectActiveByIdsAndFilters(any(), any(), any(), any());
        verify(aiServiceClient, never()).discoverRelations(any(), any());
    }

    @Test
    void unknownDuplicateOrSourceIdsInvalidateWholeAiResult() {
        List<List<Long>> invalidResponses = List.of(
                List.of(999L),
                List.of(456L, 456L),
                List.of(123L)
        );

        for (List<Long> invalidIds : invalidResponses) {
            resetValidDiscoveryForRepeatedCall();
            when(aiServiceClient.discoverRelations(any(), any())).thenReturn(
                    new AiRelationDiscoveryResponse(invalidIds)
            );

            assertThrows(
                    AiServiceUnavailableException.class,
                    () -> service.discoverRelations(123L, 20)
            );
        }
        verify(contentRelationService, never()).ensureRelatedTo(any(), any());
    }

    private void resetValidDiscoveryForRepeatedCall() {
        InboxItem source = source();
        when(inboxItemMapper.selectById(123L)).thenReturn(source);
        when(candidateDiscoveryService.discoverCandidates(123L, 20)).thenReturn(
                List.of(new RelationDiscoveryCandidate(456L, 0.9))
        );
        when(inboxItemMapper.selectActiveByIdsAndFilters(
                List.of(456L), null, null, null
        )).thenReturn(List.of(textItem(456L, "候选", "正文")));
        when(contentRelationService.findByInboxItemId(123L)).thenReturn(List.of());
    }

    private void stubDiscovery(
            List<RelationDiscoveryCandidate> candidates,
            List<InboxItem> resolvedCandidates,
            List<ContentRelation> currentRelations
    ) {
        when(inboxItemMapper.selectById(123L)).thenReturn(source());
        when(candidateDiscoveryService.discoverCandidates(123L, 20)).thenReturn(candidates);
        when(inboxItemMapper.selectActiveByIdsAndFilters(
                candidates.stream().map(RelationDiscoveryCandidate::inboxItemId).toList(),
                null,
                null,
                null
        )).thenReturn(resolvedCandidates);
        when(contentRelationService.findByInboxItemId(123L)).thenReturn(currentRelations);
    }

    private InboxItem source() {
        return textItem(123L, "Spring 事务", "事务代理失效排查");
    }

    private InboxItem textItem(Long id, String title, String content) {
        InboxItem item = item(id, "TEXT", "ACTIVE");
        item.setTitle(title);
        item.setContent(content);
        return item;
    }

    private InboxItem item(Long id, String type, String status) {
        InboxItem item = new InboxItem();
        item.setId(id);
        item.setType(type);
        item.setStatus(status);
        return item;
    }

    private ContentRelation relation(Long leftId, Long rightId) {
        ContentRelation relation = new ContentRelation();
        relation.setLeftInboxItemId(leftId);
        relation.setRightInboxItemId(rightId);
        relation.setRelationType(RelationType.RELATED_TO);
        return relation;
    }
}

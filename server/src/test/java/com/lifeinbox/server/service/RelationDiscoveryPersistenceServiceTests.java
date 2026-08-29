package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.RelationDiscoverySuggestion;
import com.lifeinbox.server.dto.RelationPersistenceResult;
import com.lifeinbox.server.entity.RelationType;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationDiscoveryPersistenceServiceTests {

    private final RelationDiscoveryService relationDiscoveryService =
            mock(RelationDiscoveryService.class);
    private final ContentRelationService contentRelationService =
            mock(ContentRelationService.class);
    private final RelationDiscoveryPersistenceService service =
            new RelationDiscoveryPersistenceService(
                    relationDiscoveryService,
                    contentRelationService
            );

    @Test
    void task43CompletesBeforeShortPersistenceServiceIsCalled() {
        List<RelationDiscoverySuggestion> suggestions = List.of(
                suggestion(123L, 456L),
                suggestion(123L, 789L)
        );
        RelationPersistenceResult expected = new RelationPersistenceResult(
                123L,
                2,
                2,
                0,
                0,
                List.of()
        );
        when(relationDiscoveryService.discoverRelations(123L, 20)).thenReturn(
                suggestions
        );
        when(contentRelationService.ensureRelatedToBatch(
                123L,
                List.of(456L, 789L)
        )).thenReturn(expected);

        RelationPersistenceResult result = service.discoverAndPersistRelations(123L, 20);

        assertSame(expected, result);
        InOrder order = inOrder(relationDiscoveryService, contentRelationService);
        order.verify(relationDiscoveryService).discoverRelations(123L, 20);
        order.verify(contentRelationService).ensureRelatedToBatch(
                123L,
                List.of(456L, 789L)
        );
    }

    @Test
    void emptyDiscoveryStillUsesFinalSourceValidationWithoutDeletingAnything() {
        RelationPersistenceResult expected = new RelationPersistenceResult(
                123L,
                0,
                0,
                0,
                0,
                List.of()
        );
        when(relationDiscoveryService.discoverRelations(123L, 20)).thenReturn(List.of());
        when(contentRelationService.ensureRelatedToBatch(123L, List.of())).thenReturn(
                expected
        );

        assertSame(expected, service.discoverAndPersistRelations(123L, 20));
        verify(contentRelationService).ensureRelatedToBatch(123L, List.of());
    }

    @Test
    void providerFailureNeverStartsPersistence() {
        when(relationDiscoveryService.discoverRelations(123L, 20)).thenThrow(
                new AiServiceUnavailableException("provider unavailable")
        );

        assertThrows(
                AiServiceUnavailableException.class,
                () -> service.discoverAndPersistRelations(123L, 20)
        );
        verify(contentRelationService, never()).ensureRelatedToBatch(any(), any());
    }

    @Test
    void malformedSuggestionSourceOrTypeInvalidatesWholeResult() {
        List<List<RelationDiscoverySuggestion>> invalidResults = List.of(
                List.of(suggestion(999L, 456L)),
                List.of(new RelationDiscoverySuggestion(123L, 456L, null))
        );

        for (List<RelationDiscoverySuggestion> invalidResult : invalidResults) {
            when(relationDiscoveryService.discoverRelations(123L, 20)).thenReturn(
                    invalidResult
            );
            assertThrows(
                    AiServiceUnavailableException.class,
                    () -> service.discoverAndPersistRelations(123L, 20)
            );
        }
        verify(contentRelationService, never()).ensureRelatedToBatch(any(), any());
    }

    @Test
    void invalidSelfOrDuplicateSuggestionTargetInvalidatesWholeResult() {
        List<List<RelationDiscoverySuggestion>> invalidResults = List.of(
                List.of(suggestion(123L, null)),
                List.of(suggestion(123L, 0L)),
                List.of(suggestion(123L, 123L)),
                List.of(suggestion(123L, 456L), suggestion(123L, 456L))
        );

        for (List<RelationDiscoverySuggestion> invalidResult : invalidResults) {
            when(relationDiscoveryService.discoverRelations(123L, 20)).thenReturn(
                    invalidResult
            );
            assertThrows(
                    AiServiceUnavailableException.class,
                    () -> service.discoverAndPersistRelations(123L, 20)
            );
        }
        verify(contentRelationService, never()).ensureRelatedToBatch(any(), any());
    }

    @Test
    void oversizedSuggestionResultInvalidatesWholeResult() {
        List<RelationDiscoverySuggestion> suggestions = new ArrayList<>();
        for (long targetId = 1L;
             targetId <= RelationDiscoveryLimits.MAX_CANDIDATES + 1L;
             targetId++) {
            suggestions.add(suggestion(123L, targetId));
        }
        when(relationDiscoveryService.discoverRelations(123L, 20)).thenReturn(
                suggestions
        );

        assertThrows(
                AiServiceUnavailableException.class,
                () -> service.discoverAndPersistRelations(123L, 20)
        );
        verify(contentRelationService, never()).ensureRelatedToBatch(any(), any());
    }

    @Test
    void orchestrationEntryPointDoesNotOpenATransactionAroundTask43() throws Exception {
        Method method = RelationDiscoveryPersistenceService.class.getMethod(
                "discoverAndPersistRelations",
                Long.class,
                Integer.class
        );

        assertNull(RelationDiscoveryPersistenceService.class.getAnnotation(
                Transactional.class
        ));
        assertNull(method.getAnnotation(Transactional.class));
    }

    private RelationDiscoverySuggestion suggestion(Long sourceId, Long targetId) {
        return new RelationDiscoverySuggestion(
                sourceId,
                targetId,
                RelationType.RELATED_TO
        );
    }
}

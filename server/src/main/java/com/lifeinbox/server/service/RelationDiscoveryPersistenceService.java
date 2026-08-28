package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.RelationDiscoverySuggestion;
import com.lifeinbox.server.dto.RelationPersistenceResult;
import com.lifeinbox.server.entity.RelationType;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Task 44 同步编排入口：先在事务外完成 Task 43，再进入 ContentRelationService 的短事务。
 */
@Service
public class RelationDiscoveryPersistenceService {

    private final RelationDiscoveryService relationDiscoveryService;
    private final ContentRelationService contentRelationService;

    public RelationDiscoveryPersistenceService(
            RelationDiscoveryService relationDiscoveryService,
            ContentRelationService contentRelationService
    ) {
        this.relationDiscoveryService = relationDiscoveryService;
        this.contentRelationService = contentRelationService;
    }

    public RelationPersistenceResult discoverAndPersistRelations(
            Long sourceInboxItemId,
            Integer limit
    ) {
        // 此类不声明事务，FastAPI/LLM/Qdrant 全部完成后才调用事务化持久层。
        List<RelationDiscoverySuggestion> suggestions =
                relationDiscoveryService.discoverRelations(sourceInboxItemId, limit);
        List<Long> targetIds = validateAndExtractTargetIds(
                sourceInboxItemId,
                suggestions
        );
        return contentRelationService.ensureRelatedToBatch(sourceInboxItemId, targetIds);
    }

    private List<Long> validateAndExtractTargetIds(
            Long sourceInboxItemId,
            List<RelationDiscoverySuggestion> suggestions
    ) {
        if (suggestions == null
                || suggestions.size() > RelationDiscoveryLimits.MAX_CANDIDATES) {
            throw invalidDiscoveryResult();
        }
        for (RelationDiscoverySuggestion suggestion : suggestions) {
            if (suggestion == null
                    || !Objects.equals(sourceInboxItemId, suggestion.sourceInboxItemId())
                    || suggestion.relationType() != RelationType.RELATED_TO) {
                // Source 或类型错位属于 Task 43 Contract 破坏，不能部分信任后继续落库。
                throw invalidDiscoveryResult();
            }
        }
        return suggestions.stream()
                .map(RelationDiscoverySuggestion::targetInboxItemId)
                .toList();
    }

    private AiServiceUnavailableException invalidDiscoveryResult() {
        return new AiServiceUnavailableException("Relation Discovery Suggestion 结果无效");
    }
}

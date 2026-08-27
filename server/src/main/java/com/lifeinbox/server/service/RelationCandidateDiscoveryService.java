package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.AiVectorNeighborCandidate;
import com.lifeinbox.server.dto.AiVectorNeighborResponse;
import com.lifeinbox.server.dto.RelationDiscoveryCandidate;
import com.lifeinbox.server.entity.ContentRelation;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.RelationType;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将 Qdrant 邻居收敛为 ACTIVE、未建立 RELATED_TO 的运行时候选。
 * 本服务只发现候选，不写 content_relation，也不把相似度当成业务关系事实。
 */
@Service
public class RelationCandidateDiscoveryService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final int DEFAULT_CANDIDATE_LIMIT = RelationDiscoveryLimits.MAX_CANDIDATES;

    private final InboxItemMapper inboxItemMapper;
    private final ContentRelationService contentRelationService;
    private final AiServiceClient aiServiceClient;

    public RelationCandidateDiscoveryService(
            InboxItemMapper inboxItemMapper,
            ContentRelationService contentRelationService,
            AiServiceClient aiServiceClient
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.contentRelationService = contentRelationService;
        this.aiServiceClient = aiServiceClient;
    }

    public List<RelationDiscoveryCandidate> discoverCandidates(
            Long sourceInboxItemId,
            Integer limit
    ) {
        requirePositiveId(sourceInboxItemId);
        int normalizedLimit = normalizeLimit(limit);

        InboxItem source = inboxItemMapper.selectById(sourceInboxItemId);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source InboxItem 不存在");
        }
        if (!STATUS_ACTIVE.equals(source.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "只有 ACTIVE InboxItem 可以发现 Relation Candidate"
            );
        }

        AiVectorNeighborResponse response = aiServiceClient.findVectorNeighbors(
                sourceInboxItemId,
                normalizedLimit
        );
        if (!Boolean.TRUE.equals(response.sourceIndexed())) {
            return List.of();
        }

        List<RankedNeighbor> rankedNeighbors = normalizeNeighbors(
                sourceInboxItemId,
                response.results()
        );
        if (rankedNeighbors.isEmpty()) {
            return List.of();
        }

        List<Long> candidateIds = rankedNeighbors.stream()
                .map(RankedNeighbor::inboxItemId)
                .toList();
        List<InboxItem> resolvedItems = inboxItemMapper.selectActiveByIdsAndFilters(
                candidateIds,
                null,
                null,
                null
        );
        if (resolvedItems == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Relation Candidate 回查失败"
            );
        }
        Map<Long, InboxItem> activeItems = activeItemsById(resolvedItems);
        Set<Long> relatedIds = relatedInboxItemIds(
                sourceInboxItemId,
                contentRelationService.findByInboxItemId(sourceInboxItemId)
        );

        List<RelationDiscoveryCandidate> candidates = new ArrayList<>();
        for (RankedNeighbor neighbor : rankedNeighbors) {
            InboxItem item = activeItems.get(neighbor.inboxItemId());
            if (item == null || relatedIds.contains(neighbor.inboxItemId())) {
                continue;
            }
            candidates.add(new RelationDiscoveryCandidate(
                    neighbor.inboxItemId(),
                    neighbor.semanticScore()
            ));
            // 先完成所有权威过滤，再应用最终 limit，避免过滤项挤占有效结果。
            if (candidates.size() == normalizedLimit) {
                break;
            }
        }
        return List.copyOf(candidates);
    }

    private List<RankedNeighbor> normalizeNeighbors(
            Long sourceInboxItemId,
            List<AiVectorNeighborCandidate> rawCandidates
    ) {
        if (rawCandidates == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Vector Neighbor 响应无效"
            );
        }

        Map<Long, Double> bestScoreById = new LinkedHashMap<>();
        for (AiVectorNeighborCandidate candidate : rawCandidates) {
            if (candidate == null
                    || candidate.inboxItemId() == null
                    || candidate.inboxItemId() <= 0
                    || candidate.score() == null
                    || !Double.isFinite(candidate.score())) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Vector Neighbor 响应无效"
                );
            }
            if (sourceInboxItemId.equals(candidate.inboxItemId())) {
                // Python 已过滤；Java 再防御一次，避免 Source 进入候选。
                continue;
            }
            bestScoreById.merge(candidate.inboxItemId(), candidate.score(), Math::max);
        }

        return bestScoreById.entrySet().stream()
                .map(entry -> new RankedNeighbor(entry.getKey(), entry.getValue()))
                .sorted(Comparator
                        .comparingDouble(RankedNeighbor::semanticScore)
                        .reversed()
                        .thenComparingLong(RankedNeighbor::inboxItemId))
                .toList();
    }

    private Map<Long, InboxItem> activeItemsById(List<InboxItem> resolvedItems) {
        Map<Long, InboxItem> activeItems = new HashMap<>();
        for (InboxItem item : resolvedItems) {
            if (item == null
                    || item.getId() == null
                    || item.getId() <= 0
                    || !STATUS_ACTIVE.equals(item.getStatus())) {
                continue;
            }
            activeItems.putIfAbsent(item.getId(), item);
        }
        return activeItems;
    }

    private Set<Long> relatedInboxItemIds(
            Long sourceInboxItemId,
            List<ContentRelation> relations
    ) {
        if (relations == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Relation 查询失败"
            );
        }

        Set<Long> relatedIds = new HashSet<>();
        for (ContentRelation relation : relations) {
            if (relation == null || relation.getRelationType() != RelationType.RELATED_TO) {
                continue;
            }
            if (sourceInboxItemId.equals(relation.getLeftInboxItemId())) {
                relatedIds.add(relation.getRightInboxItemId());
            } else if (sourceInboxItemId.equals(relation.getRightInboxItemId())) {
                relatedIds.add(relation.getLeftInboxItemId());
            }
        }
        relatedIds.remove(null);
        relatedIds.remove(sourceInboxItemId);
        return relatedIds;
    }

    private void requirePositiveId(Long inboxItemId) {
        if (inboxItemId == null || inboxItemId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "InboxItem ID 必须是正整数"
            );
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_CANDIDATE_LIMIT;
        }
        if (limit < 1 || limit > RelationDiscoveryLimits.MAX_CANDIDATES) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Relation Candidate limit 必须在 1 到 20 之间"
            );
        }
        return limit;
    }

    private record RankedNeighbor(Long inboxItemId, double semanticScore) {
    }
}

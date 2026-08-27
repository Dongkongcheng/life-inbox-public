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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 复用 Task 42 候选，用一次 LLM 调用生成 RELATED_TO 运行时建议。
 * 本服务不创建 ContentRelation，也不拥有后台状态或自动处理生命周期。
 */
@Service
public class RelationDiscoveryService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final int DEFAULT_CANDIDATE_LIMIT = RelationDiscoveryLimits.MAX_CANDIDATES;

    private final InboxItemMapper inboxItemMapper;
    private final ContentRelationService contentRelationService;
    private final RelationCandidateDiscoveryService candidateDiscoveryService;
    private final RelationDiscoveryTextBuilder textBuilder;
    private final AiServiceClient aiServiceClient;

    public RelationDiscoveryService(
            InboxItemMapper inboxItemMapper,
            ContentRelationService contentRelationService,
            RelationCandidateDiscoveryService candidateDiscoveryService,
            RelationDiscoveryTextBuilder textBuilder,
            AiServiceClient aiServiceClient
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.contentRelationService = contentRelationService;
        this.candidateDiscoveryService = candidateDiscoveryService;
        this.textBuilder = textBuilder;
        this.aiServiceClient = aiServiceClient;
    }

    public List<RelationDiscoverySuggestion> discoverRelations(
            Long sourceInboxItemId,
            Integer limit
    ) {
        requirePositiveId(sourceInboxItemId);
        int normalizedLimit = normalizeLimit(limit);

        InboxItem initialSource = requireActiveSource(sourceInboxItemId);
        if (textBuilder.buildSource(initialSource) == null) {
            return List.of();
        }

        List<RelationDiscoveryCandidate> discoveredCandidates =
                candidateDiscoveryService.discoverCandidates(
                        sourceInboxItemId,
                        normalizedLimit
                );
        List<Long> candidateIds = normalizeCandidateIds(
                sourceInboxItemId,
                discoveredCandidates
        );
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        // Task 42 结束后重新确认 Source，避免向 LLM 发送已归档或已删除的数据。
        InboxItem currentSource = requireActiveSource(sourceInboxItemId);
        String sourceText = textBuilder.buildSource(currentSource);
        if (sourceText == null) {
            return List.of();
        }

        List<InboxItem> resolvedCandidates = inboxItemMapper.selectActiveByIdsAndFilters(
                candidateIds,
                null,
                null,
                null
        );
        if (resolvedCandidates == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Relation Discovery Candidate 回查失败"
            );
        }

        Map<Long, InboxItem> activeCandidates = activeItemsById(resolvedCandidates);
        Set<Long> relatedIds = relatedInboxItemIds(
                sourceInboxItemId,
                contentRelationService.findByInboxItemId(sourceInboxItemId)
        );
        List<AiRelationDiscoveryItem> aiCandidates = new ArrayList<>();
        for (Long candidateId : candidateIds) {
            InboxItem candidate = activeCandidates.get(candidateId);
            if (candidate == null || relatedIds.contains(candidateId)) {
                continue;
            }
            String candidateText = textBuilder.buildCandidate(candidate);
            if (candidateText != null) {
                aiCandidates.add(new AiRelationDiscoveryItem(candidateId, candidateText));
            }
        }
        if (aiCandidates.isEmpty()) {
            return List.of();
        }

        AiRelationDiscoveryItem aiSource = new AiRelationDiscoveryItem(
                sourceInboxItemId,
                sourceText
        );
        AiRelationDiscoveryResponse response = aiServiceClient.discoverRelations(
                aiSource,
                List.copyOf(aiCandidates)
        );
        List<Long> relatedTargetIds = validateReturnedIds(
                sourceInboxItemId,
                aiCandidates,
                response
        );

        return relatedTargetIds.stream()
                .map(targetId -> new RelationDiscoverySuggestion(
                        sourceInboxItemId,
                        targetId,
                        RelationType.RELATED_TO
                ))
                .toList();
    }

    private InboxItem requireActiveSource(Long sourceInboxItemId) {
        InboxItem source = inboxItemMapper.selectById(sourceInboxItemId);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source InboxItem 不存在");
        }
        if (!STATUS_ACTIVE.equals(source.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "只有 ACTIVE InboxItem 可以发现 Relation"
            );
        }
        return source;
    }

    private List<Long> normalizeCandidateIds(
            Long sourceInboxItemId,
            List<RelationDiscoveryCandidate> candidates
    ) {
        if (candidates == null || candidates.size() > RelationDiscoveryLimits.MAX_CANDIDATES) {
            throw invalidCandidateResult();
        }

        List<Long> ids = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        for (RelationDiscoveryCandidate candidate : candidates) {
            if (candidate == null
                    || candidate.inboxItemId() == null
                    || candidate.inboxItemId() <= 0
                    || sourceInboxItemId.equals(candidate.inboxItemId())
                    || !Double.isFinite(candidate.semanticScore())
                    || !seenIds.add(candidate.inboxItemId())) {
                throw invalidCandidateResult();
            }
            ids.add(candidate.inboxItemId());
        }
        return List.copyOf(ids);
    }

    private Map<Long, InboxItem> activeItemsById(List<InboxItem> items) {
        Map<Long, InboxItem> activeItems = new HashMap<>();
        for (InboxItem item : items) {
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

    private List<Long> validateReturnedIds(
            Long sourceInboxItemId,
            List<AiRelationDiscoveryItem> sentCandidates,
            AiRelationDiscoveryResponse response
    ) {
        if (response == null || response.relatedTargetInboxItemIds() == null) {
            throw invalidAiResult();
        }
        Set<Long> suppliedIds = new HashSet<>();
        sentCandidates.forEach(candidate -> suppliedIds.add(candidate.inboxItemId()));
        Set<Long> returnedIds = new HashSet<>();
        for (Long returnedId : response.relatedTargetInboxItemIds()) {
            if (returnedId == null
                    || returnedId <= 0
                    || sourceInboxItemId.equals(returnedId)
                    || !suppliedIds.contains(returnedId)
                    || !returnedIds.add(returnedId)) {
                throw invalidAiResult();
            }
        }
        return List.copyOf(response.relatedTargetInboxItemIds());
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
                    "Relation Discovery limit 必须在 1 到 20 之间"
            );
        }
        return limit;
    }

    private AiServiceUnavailableException invalidCandidateResult() {
        return new AiServiceUnavailableException("Relation Candidate 结果无效");
    }

    private AiServiceUnavailableException invalidAiResult() {
        return new AiServiceUnavailableException("AI 服务返回了无效的 Relation Discovery 结果");
    }
}

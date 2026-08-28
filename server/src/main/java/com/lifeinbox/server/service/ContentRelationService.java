package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.RelationPersistenceResult;
import com.lifeinbox.server.entity.ContentRelation;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.RelationType;
import com.lifeinbox.server.mapper.ContentRelationMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 第一版 Relation 业务边界：只建立 ACTIVE InboxItem 之间的 RELATED_TO。 */
@Service
public class ContentRelationService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final InboxItemMapper inboxItemMapper;
    private final ContentRelationMapper contentRelationMapper;

    public ContentRelationService(
            InboxItemMapper inboxItemMapper,
            ContentRelationMapper contentRelationMapper
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.contentRelationMapper = contentRelationMapper;
    }

    /**
     * RELATED_TO 是对称关系，因此所有写入先把较小 ID 放在 left。
     * 行锁串行化端点生命周期变化，唯一约束再防止并发或其他写路径产生重复 Pair。
     */
    @Transactional
    public ContentRelation ensureRelatedTo(Long firstInboxItemId, Long secondInboxItemId) {
        CanonicalPair pair = canonicalize(firstInboxItemId, secondInboxItemId);
        List<InboxItem> endpoints = inboxItemMapper.selectRelationEndpointsForUpdate(
                pair.leftInboxItemId(),
                pair.rightInboxItemId()
        );
        requireActiveEndpoints(pair, endpoints);

        ContentRelation existing = selectCanonicalPair(pair);
        if (existing != null) {
            return existing;
        }

        ContentRelation relation = new ContentRelation();
        relation.setLeftInboxItemId(pair.leftInboxItemId());
        relation.setRightInboxItemId(pair.rightInboxItemId());
        relation.setRelationType(RelationType.RELATED_TO);

        try {
            if (contentRelationMapper.insert(relation) != 1) {
                throw persistenceFailure(null);
            }
        } catch (DuplicateKeyException exception) {
            // 普通重复会在插入前返回；这里处理并发或其他合法写路径触发的唯一键竞争。
            ContentRelation concurrent = selectCanonicalPair(pair);
            if (concurrent != null) {
                return concurrent;
            }
            throw persistenceFailure(exception);
        }

        ContentRelation saved = selectCanonicalPair(pair);
        if (saved == null) {
            throw persistenceFailure(null);
        }
        return saved;
    }

    /**
     * AI 已在事务外完成；这里只在一个短事务中重新锁定当前业务状态并增量写入。
     * Source 失效终止整批，单个 Target 失效只跳过，已有关系属于幂等成功。
     */
    @Transactional
    public RelationPersistenceResult ensureRelatedToBatch(
            Long sourceInboxItemId,
            List<Long> suggestedTargetIds
    ) {
        requirePositiveId(sourceInboxItemId);
        if (suggestedTargetIds == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Relation Suggestion 列表不能为空"
            );
        }

        int discoveredCount = suggestedTargetIds.size();
        NormalizedTargets normalizedTargets = normalizeTargets(
                sourceInboxItemId,
                suggestedTargetIds
        );
        List<Long> endpointIds = new ArrayList<>();
        endpointIds.add(sourceInboxItemId);
        endpointIds.addAll(normalizedTargets.targetIds());
        endpointIds.sort(Long::compareTo);

        List<InboxItem> lockedEndpoints = inboxItemMapper
                .selectRelationEndpointsForUpdateByIds(endpointIds);
        if (lockedEndpoints == null) {
            throw persistenceFailure(null);
        }
        Map<Long, InboxItem> endpointById = endpointsById(lockedEndpoints);
        requireActiveSource(sourceInboxItemId, endpointById.get(sourceInboxItemId));

        int skippedInvalidCount = normalizedTargets.skippedInvalidCount();
        List<Long> activeTargetIds = new ArrayList<>();
        for (Long targetId : normalizedTargets.targetIds()) {
            InboxItem target = endpointById.get(targetId);
            if (target == null || !STATUS_ACTIVE.equals(target.getStatus())) {
                // Derived Suggestion 的单个目标可能在 AI 期间失效，不应浪费其他合法目标。
                skippedInvalidCount++;
                continue;
            }
            activeTargetIds.add(targetId);
        }

        if (activeTargetIds.isEmpty()) {
            return new RelationPersistenceResult(
                    sourceInboxItemId,
                    discoveredCount,
                    0,
                    0,
                    skippedInvalidCount,
                    List.of()
            );
        }

        List<ContentRelation> currentRelations = contentRelationMapper.selectByInboxItemId(
                sourceInboxItemId
        );
        if (currentRelations == null) {
            throw persistenceFailure(null);
        }
        Map<Long, ContentRelation> existingByTargetId = existingRelationsByTargetId(
                sourceInboxItemId,
                currentRelations
        );

        int persistedNewCount = 0;
        int alreadyExistingCount = 0;
        List<ContentRelation> ensuredRelations = new ArrayList<>();
        for (Long targetId : activeTargetIds) {
            ContentRelation existing = existingByTargetId.get(targetId);
            if (existing != null) {
                alreadyExistingCount++;
                ensuredRelations.add(existing);
                continue;
            }

            EnsureOutcome outcome = insertOrReuseCanonicalPair(
                    canonicalize(sourceInboxItemId, targetId)
            );
            ensuredRelations.add(outcome.relation());
            if (outcome.inserted()) {
                persistedNewCount++;
            } else {
                // UNIQUE 竞争由 Task 41 的 Canonical Pair 查询收敛为幂等成功。
                alreadyExistingCount++;
            }
        }

        return new RelationPersistenceResult(
                sourceInboxItemId,
                discoveredCount,
                persistedNewCount,
                alreadyExistingCount,
                skippedInvalidCount,
                ensuredRelations
        );
    }

    /** 查询不要求端点仍为 ACTIVE；Archive 保留既有 Relation，Delete 由外键级联清理。 */
    public List<ContentRelation> findByInboxItemId(Long inboxItemId) {
        requirePositiveId(inboxItemId);
        List<ContentRelation> relations = contentRelationMapper.selectByInboxItemId(inboxItemId);
        if (relations == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Relation 查询失败"
            );
        }
        return List.copyOf(relations);
    }

    private CanonicalPair canonicalize(Long firstInboxItemId, Long secondInboxItemId) {
        requirePositiveId(firstInboxItemId);
        requirePositiveId(secondInboxItemId);
        if (firstInboxItemId.equals(secondInboxItemId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "InboxItem 不能与自身建立 Relation"
            );
        }
        return firstInboxItemId < secondInboxItemId
                ? new CanonicalPair(firstInboxItemId, secondInboxItemId)
                : new CanonicalPair(secondInboxItemId, firstInboxItemId);
    }

    private void requirePositiveId(Long inboxItemId) {
        if (inboxItemId == null || inboxItemId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "InboxItem ID 必须是正整数"
            );
        }
    }

    private void requireActiveEndpoints(CanonicalPair pair, List<InboxItem> endpoints) {
        if (endpoints == null || endpoints.size() != 2
                || endpoints.stream().noneMatch(item -> pair.leftInboxItemId().equals(item.getId()))
                || endpoints.stream().noneMatch(item -> pair.rightInboxItemId().equals(item.getId()))) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Relation Endpoint InboxItem 不存在"
            );
        }
        if (endpoints.stream().anyMatch(item -> !STATUS_ACTIVE.equals(item.getStatus()))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "只有 ACTIVE InboxItem 可以建立新 Relation"
            );
        }
    }

    private ContentRelation selectCanonicalPair(CanonicalPair pair) {
        return contentRelationMapper.selectCanonicalPair(
                pair.leftInboxItemId(),
                pair.rightInboxItemId(),
                RelationType.RELATED_TO
        );
    }

    private NormalizedTargets normalizeTargets(
            Long sourceInboxItemId,
            List<Long> suggestedTargetIds
    ) {
        Set<Long> uniqueTargets = new LinkedHashSet<>();
        int skippedInvalidCount = 0;
        for (Long targetId : suggestedTargetIds) {
            if (targetId == null
                    || targetId <= 0
                    || sourceInboxItemId.equals(targetId)
                    || !uniqueTargets.add(targetId)) {
                skippedInvalidCount++;
            }
        }
        return new NormalizedTargets(List.copyOf(uniqueTargets), skippedInvalidCount);
    }

    private Map<Long, InboxItem> endpointsById(List<InboxItem> endpoints) {
        Map<Long, InboxItem> endpointById = new HashMap<>();
        for (InboxItem endpoint : endpoints) {
            if (endpoint != null && endpoint.getId() != null) {
                endpointById.putIfAbsent(endpoint.getId(), endpoint);
            }
        }
        return endpointById;
    }

    private void requireActiveSource(Long sourceInboxItemId, InboxItem source) {
        if (source == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Source InboxItem 不存在"
            );
        }
        if (!STATUS_ACTIVE.equals(source.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "只有 ACTIVE InboxItem 可以持久化新 Relation"
            );
        }
        if (!sourceInboxItemId.equals(source.getId())) {
            throw persistenceFailure(null);
        }
    }

    private Map<Long, ContentRelation> existingRelationsByTargetId(
            Long sourceInboxItemId,
            List<ContentRelation> relations
    ) {
        Map<Long, ContentRelation> existingByTargetId = new HashMap<>();
        for (ContentRelation relation : relations) {
            if (relation == null || relation.getRelationType() != RelationType.RELATED_TO) {
                continue;
            }
            Long targetId = null;
            if (sourceInboxItemId.equals(relation.getLeftInboxItemId())) {
                targetId = relation.getRightInboxItemId();
            } else if (sourceInboxItemId.equals(relation.getRightInboxItemId())) {
                targetId = relation.getLeftInboxItemId();
            }
            if (targetId != null && !sourceInboxItemId.equals(targetId)) {
                existingByTargetId.putIfAbsent(targetId, relation);
            }
        }
        return existingByTargetId;
    }

    private EnsureOutcome insertOrReuseCanonicalPair(CanonicalPair pair) {
        ContentRelation relation = new ContentRelation();
        relation.setLeftInboxItemId(pair.leftInboxItemId());
        relation.setRightInboxItemId(pair.rightInboxItemId());
        relation.setRelationType(RelationType.RELATED_TO);

        try {
            if (contentRelationMapper.insert(relation) != 1) {
                throw persistenceFailure(null);
            }
            return new EnsureOutcome(relation, true);
        } catch (DuplicateKeyException exception) {
            ContentRelation concurrent = selectCanonicalPair(pair);
            if (concurrent != null) {
                return new EnsureOutcome(concurrent, false);
            }
            throw persistenceFailure(exception);
        }
    }

    private ResponseStatusException persistenceFailure(Throwable cause) {
        return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Relation 保存失败",
                cause
        );
    }

    private record CanonicalPair(Long leftInboxItemId, Long rightInboxItemId) {
    }

    private record NormalizedTargets(List<Long> targetIds, int skippedInvalidCount) {
    }

    private record EnsureOutcome(ContentRelation relation, boolean inserted) {
    }
}

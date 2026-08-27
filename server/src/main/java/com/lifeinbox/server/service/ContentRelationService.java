package com.lifeinbox.server.service;

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

import java.util.List;

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

    private ResponseStatusException persistenceFailure(Throwable cause) {
        return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Relation 保存失败",
                cause
        );
    }

    private record CanonicalPair(Long leftInboxItemId, Long rightInboxItemId) {
    }
}

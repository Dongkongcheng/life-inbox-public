package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.RelatedInboxItemResponse;
import com.lifeinbox.server.dto.RelatedInboxItemSummaryResponse;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.RelationType;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只从 MySQL 已持久化状态构建 Related Items 产品视图，不参与发现或 Relation 写入。 */
@Service
public class RelatedInboxItemService {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 20;
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final InboxItemMapper inboxItemMapper;

    public RelatedInboxItemService(InboxItemMapper inboxItemMapper) {
        this.inboxItemMapper = inboxItemMapper;
    }

    public List<RelatedInboxItemResponse> listRelated(
            Long sourceInboxItemId,
            Integer limit
    ) {
        int normalizedLimit = normalizeLimit(limit);
        requireActiveSource(sourceInboxItemId);

        List<InboxItem> relatedItems = inboxItemMapper.selectRelatedActiveItems(
                sourceInboxItemId,
                RelationType.RELATED_TO,
                normalizedLimit
        );
        if (relatedItems == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Related Items 查询失败"
            );
        }

        Map<Long, RelatedInboxItemResponse> uniqueResponses = new LinkedHashMap<>();
        for (InboxItem item : relatedItems) {
            if (item == null
                    || item.getId() == null
                    || item.getId() <= 0
                    || sourceInboxItemId.equals(item.getId())
                    || !STATUS_ACTIVE.equals(item.getStatus())) {
                // JOIN 已完成正常过滤；这里防御历史脏数据，不能让一个异常 Target 破坏整个产品列表。
                continue;
            }
            uniqueResponses.putIfAbsent(item.getId(), toResponse(item));
            if (uniqueResponses.size() == normalizedLimit) {
                break;
            }
        }
        return List.copyOf(uniqueResponses.values());
    }

    private void requireActiveSource(Long sourceInboxItemId) {
        if (sourceInboxItemId == null || sourceInboxItemId <= 0) {
            throw sourceNotFound();
        }
        InboxItem source = inboxItemMapper.selectById(sourceInboxItemId);
        if (source == null || !STATUS_ACTIVE.equals(source.getStatus())) {
            // 与现有 ACTIVE-only Inbox nested API 一致：归档 Source 对普通产品视图也表现为不存在。
            throw sourceNotFound();
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Related Items limit 必须在 1 到 20 之间"
            );
        }
        return limit;
    }

    private RelatedInboxItemResponse toResponse(InboxItem item) {
        RelatedInboxItemSummaryResponse summary = new RelatedInboxItemSummaryResponse(
                item.getId(),
                item.getType(),
                item.getTitle(),
                item.getSummary(),
                item.getCategory(),
                InboxItemPreviewBuilder.build(item),
                Integer.valueOf(1).equals(item.getFavorite()),
                item.getCreatedTime()
        );
        return new RelatedInboxItemResponse(RelationType.RELATED_TO, summary);
    }

    private ResponseStatusException sourceNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
    }
}

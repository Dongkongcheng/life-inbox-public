package com.lifeinbox.server.dto;

import com.lifeinbox.server.entity.RelationType;

/** 对称 Relation 的产品响应只表达“相关条目”，不泄露 left/right 存储方向。 */
public record RelatedInboxItemResponse(
        RelationType relationType,
        RelatedInboxItemSummaryResponse relatedInboxItem
) {
}

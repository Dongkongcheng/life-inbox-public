package com.lifeinbox.server.dto;

import com.lifeinbox.server.entity.RelationType;

/** Task 43 的运行时建议，不是已持久化的 ContentRelation。 */
public record RelationDiscoverySuggestion(
        Long sourceInboxItemId,
        Long targetInboxItemId,
        RelationType relationType
) {
}

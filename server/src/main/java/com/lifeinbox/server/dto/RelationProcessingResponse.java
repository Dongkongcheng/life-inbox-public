package com.lifeinbox.server.dto;

import com.lifeinbox.server.entity.RelationProcessingStatus;

/** 手动 Relation Discovery 只返回产品需要的状态与计数，不暴露 Attempt 或 Provider 元数据。 */
public record RelationProcessingResponse(
        RelationProcessingStatus relationStatus,
        int discoveredCount,
        int persistedNewCount,
        int alreadyExistingCount,
        int skippedInvalidCount
) {
}

package com.lifeinbox.server.dto;

import com.lifeinbox.server.entity.ContentRelation;

import java.util.List;

/** Task 44 内部运行时结果，用计数区分新增、复用和业务失效目标。 */
public record RelationPersistenceResult(
        Long sourceInboxItemId,
        int discoveredCount,
        int persistedNewCount,
        int alreadyExistingCount,
        int skippedInvalidCount,
        List<ContentRelation> relations
) {
    public RelationPersistenceResult {
        relations = relations == null ? List.of() : List.copyOf(relations);
    }
}

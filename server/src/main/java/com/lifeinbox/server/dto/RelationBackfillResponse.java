package com.lifeinbox.server.dto;

/** Backfill 只返回有界调度摘要，不暴露 Attempt、Vector 或 Provider 数据。 */
public record RelationBackfillResponse(
        int requestedLimit,
        int scannedCount,
        int scheduledCount,
        int skippedNotReadyCount,
        int claimConflictCount
) {
}

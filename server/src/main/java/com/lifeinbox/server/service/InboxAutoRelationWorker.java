package com.lifeinbox.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 后台入口复用统一 Relation 编排；异常只记录，绝不影响 Capture、Analyze 或 Vector。 */
@Service
public class InboxAutoRelationWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxAutoRelationWorker.class);

    private final RelationProcessingService relationProcessingService;

    public InboxAutoRelationWorker(RelationProcessingService relationProcessingService) {
        this.relationProcessingService = relationProcessingService;
    }

    public void discover(Long inboxItemId) {
        try {
            relationProcessingService.processAutomatic(inboxItemId);
        } catch (RuntimeException exception) {
            // 统一 Service 已按 Attempt 收尾；既有 Relation 永远不会因本次失败被删除。
            LOGGER.warn("InboxItem {} 的自动 Relation Discovery 失败", inboxItemId, exception);
        }
    }

    /** Backfill 在 Web 线程完成 Claim 后，把明确 Attempt 交给同一个失败隔离 Worker。 */
    public void discoverClaimed(Long inboxItemId, String attemptId) {
        try {
            relationProcessingService.processClaimed(inboxItemId, attemptId);
        } catch (RuntimeException exception) {
            LOGGER.warn("InboxItem {} 的 Backfill Relation Discovery 失败", inboxItemId, exception);
        }
    }
}

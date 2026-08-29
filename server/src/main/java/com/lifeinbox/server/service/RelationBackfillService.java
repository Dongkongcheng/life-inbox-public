package com.lifeinbox.server.service;

import com.lifeinbox.server.config.AiBackgroundConfiguration;
import com.lifeinbox.server.dto.RelationBackfillResponse;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.RelationProcessingStatus;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/** 显式历史 Relation 补处理：有界选取、检查既有 Vector、统一 Claim 后投递既有执行器。 */
@Service
public class RelationBackfillService {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 20;
    static final int SCAN_MULTIPLIER = 5;
    static final int MAX_SCAN_LIMIT = 100;

    private static final Logger LOGGER = LoggerFactory.getLogger(RelationBackfillService.class);
    private static final String SCHEDULING_FAILED_MESSAGE = "Relation Backfill 队列繁忙，请稍后重试";

    private final InboxItemMapper inboxItemMapper;
    private final RelationCandidateDiscoveryService candidateDiscoveryService;
    private final RelationProcessingStatusService statusService;
    private final TaskExecutor taskExecutor;
    private final InboxAutoRelationWorker worker;

    public RelationBackfillService(
            InboxItemMapper inboxItemMapper,
            RelationCandidateDiscoveryService candidateDiscoveryService,
            RelationProcessingStatusService statusService,
            @Qualifier(AiBackgroundConfiguration.AI_TASK_EXECUTOR) TaskExecutor taskExecutor,
            InboxAutoRelationWorker worker
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.candidateDiscoveryService = candidateDiscoveryService;
        this.statusService = statusService;
        this.taskExecutor = taskExecutor;
        this.worker = worker;
    }

    public RelationBackfillResponse schedule(Integer limit) {
        int requestedLimit = normalizeLimit(limit);
        int scanLimit = scanLimitFor(requestedLimit);
        List<InboxItem> candidates = inboxItemMapper.selectRelationBackfillCandidates(
                RelationProcessingStatus.NOT_PROCESSED,
                scanLimit
        );
        if (candidates == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Relation Backfill 查询失败"
            );
        }

        int scannedCount = 0;
        int scheduledCount = 0;
        int skippedNotReadyCount = 0;
        int claimConflictCount = 0;
        for (InboxItem candidate : candidates) {
            if (scheduledCount >= requestedLimit) {
                break;
            }
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            scannedCount++;

            // Missing Vector 不是 Relation 失败；保持 NOT_PROCESSED，等待独立 Vector 生命周期处理。
            if (!candidateDiscoveryService.isSourceVectorReady(candidate.getId())) {
                skippedNotReadyCount++;
                continue;
            }

            Optional<String> attemptId = statusService.claimAutomatic(candidate.getId());
            if (attemptId.isEmpty()) {
                // Automatic、Manual 或另一 Backfill 已先取得所有权时，本批只跳过。
                claimConflictCount++;
                continue;
            }

            try {
                taskExecutor.execute(() -> worker.discoverClaimed(
                        candidate.getId(),
                        attemptId.get()
                ));
                scheduledCount++;
            } catch (RuntimeException exception) {
                // Claim 后排队失败必须结束当前 Attempt，不能让条目永久停在 PROCESSING。
                boolean failed = statusService.markFailed(
                        candidate.getId(),
                        attemptId.get(),
                        SCHEDULING_FAILED_MESSAGE
                );
                LOGGER.warn(
                        "Relation Backfill 排队失败，InboxItem={}，状态已收尾={}",
                        candidate.getId(),
                        failed,
                        exception
                );
            }
        }

        return new RelationBackfillResponse(
                requestedLimit,
                scannedCount,
                scheduledCount,
                skippedNotReadyCount,
                claimConflictCount
        );
    }

    static int scanLimitFor(int requestedLimit) {
        return Math.min(requestedLimit * SCAN_MULTIPLIER, MAX_SCAN_LIMIT);
    }

    private int normalizeLimit(Integer limit) {
        int normalized = limit == null ? DEFAULT_LIMIT : limit;
        if (normalized < 1 || normalized > MAX_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Relation Backfill limit 必须在 1 到 20 之间"
            );
        }
        return normalized;
    }
}

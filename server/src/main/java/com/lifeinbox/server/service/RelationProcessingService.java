package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.RelationPersistenceResult;
import com.lifeinbox.server.dto.RelationProcessingResponse;
import com.lifeinbox.server.entity.RelationProcessingStatus;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/** 手动与自动 Relation Discovery 共用编排；仅 Claim 策略和是否返回 HTTP 响应不同。 */
@Service
public class RelationProcessingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RelationProcessingService.class);

    private final RelationProcessingStatusService statusService;
    private final RelationDiscoveryPersistenceService persistenceService;

    public RelationProcessingService(
            RelationProcessingStatusService statusService,
            RelationDiscoveryPersistenceService persistenceService
    ) {
        this.statusService = statusService;
        this.persistenceService = persistenceService;
    }

    public RelationProcessingResponse processManual(Long inboxItemId) {
        String attemptId = statusService.claimManual(inboxItemId);
        return execute(inboxItemId, attemptId);
    }

    public Optional<RelationProcessingResponse> processAutomatic(Long inboxItemId) {
        Optional<String> attemptId = statusService.claimAutomatic(inboxItemId);
        if (attemptId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(execute(inboxItemId, attemptId.get()));
    }

    private RelationProcessingResponse execute(Long inboxItemId, String attemptId) {
        List<Long> targetIds;
        try {
            // Qdrant 邻居查询和 LLM 调用都在已提交 Claim 之后、最终短事务之前完成。
            targetIds = persistenceService.discoverTargetIdsForProcessing(inboxItemId, null);
        } catch (RuntimeException exception) {
            recordFailure(inboxItemId, attemptId, safeFailureMessage(exception), exception);
            throw exception;
        }

        try {
            RelationPersistenceResult result = persistenceService.completeAttempt(
                    inboxItemId,
                    attemptId,
                    targetIds
            );
            return new RelationProcessingResponse(
                    RelationProcessingStatus.SUCCESS,
                    result.discoveredCount(),
                    result.persistedNewCount(),
                    result.alreadyExistingCount(),
                    result.skippedInvalidCount()
            );
        } catch (RuntimeException exception) {
            recordFailure(inboxItemId, attemptId, "Relation 结果保存失败", exception);
            throw exception;
        }
    }

    private String safeFailureMessage(RuntimeException exception) {
        if (exception instanceof AiServiceUnavailableException) {
            return "Relation AI 服务暂时不可用";
        }
        if (exception instanceof ResponseStatusException responseException
                && responseException.getReason() != null) {
            return responseException.getReason();
        }
        return "Relation Discovery 失败";
    }

    private void recordFailure(
            Long inboxItemId,
            String attemptId,
            String safeMessage,
            RuntimeException originalException
    ) {
        try {
            boolean saved = statusService.markFailed(inboxItemId, attemptId, safeMessage);
            if (!saved) {
                LOGGER.info("忽略已失效 Relation Attempt 的失败结果，InboxItem={}", inboxItemId);
            }
        } catch (RuntimeException statusException) {
            originalException.addSuppressed(statusException);
            LOGGER.warn("InboxItem {} 的 Relation 失败状态保存失败", inboxItemId, statusException);
        }
    }
}

package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.AiProcessingStatus;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 管理 Analyze 开始与失败状态；每个方法都是独立、立即提交的短事务。
 * stale PROCESSING 仍只在新 Analyze 时懒恢复，不做自动重试或定时扫描。
 */
@Service
public class InboxAnalysisStatusService {

    private static final int MAX_ERROR_MESSAGE_CHARS = 255;

    private final InboxItemMapper inboxItemMapper;
    private final Duration processingStaleAfter;
    private final Clock clock;

    @Autowired
    public InboxAnalysisStatusService(
            InboxItemMapper inboxItemMapper,
            @Value("${life-inbox.ai.processing-stale-after:5m}") Duration processingStaleAfter
    ) {
        this(inboxItemMapper, processingStaleAfter, Clock.systemDefaultZone());
    }

    InboxAnalysisStatusService(
            InboxItemMapper inboxItemMapper,
            Duration processingStaleAfter,
            Clock clock
    ) {
        if (processingStaleAfter == null
                || processingStaleAfter.isZero()
                || processingStaleAfter.isNegative()) {
            throw new IllegalArgumentException("processing-stale-after 必须大于 0");
        }
        this.inboxItemMapper = inboxItemMapper;
        this.processingStaleAfter = processingStaleAfter;
        this.clock = clock;
    }

    /**
     * 在远程调用前提交 PROCESSING。条件 UPDATE 是数据库级重复请求保护，
     * 当前单机 MySQL 已足够，不需要 Redis 分布式锁；唯一 Attempt ID 使被接管的旧请求失去写权限。
     */
    @Transactional
    public String markProcessing(Long inboxItemId) {
        String attemptId = UUID.randomUUID().toString();
        LocalDateTime startedTime = LocalDateTime.now(clock);
        int updatedRows = inboxItemMapper.markAnalysisProcessing(
                inboxItemId,
                AiProcessingStatus.PROCESSING,
                attemptId,
                startedTime,
                startedTime.minus(processingStaleAfter)
        );
        if (updatedRows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI 分析正在进行中");
        }
        return attemptId;
    }

    /**
     * 失败只记录安全摘要并结束本次尝试；旧的五类 AI 结果继续保留供用户查看。
     */
    @Transactional
    public boolean markFailed(Long inboxItemId, String attemptId, String errorMessage) {
        String safeMessage = normalizeErrorMessage(errorMessage);
        int updatedRows = inboxItemMapper.markAnalysisFailed(
                inboxItemId,
                attemptId,
                AiProcessingStatus.PROCESSING,
                AiProcessingStatus.FAILED,
                safeMessage
        );
        // 返回 0 表示该 Attempt 已被接管；旧失败必须静默放弃，不能破坏新请求。
        return updatedRows == 1;
    }

    /**
     * AFTER_COMMIT 阶段已结束原 Capture 事务，因此必须用 REQUIRES_NEW 提交排队失败状态。
     * 条件 UPDATE 不会覆盖已被手工 Analyze 领取的 PROCESSING。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markAutoSchedulingFailed(Long inboxItemId, String errorMessage) {
        int updatedRows = inboxItemMapper.markAutoSchedulingFailed(
                inboxItemId,
                AiProcessingStatus.NOT_PROCESSED,
                AiProcessingStatus.FAILED,
                normalizeErrorMessage(errorMessage)
        );
        return updatedRows == 1;
    }

    /**
     * STALE 只是由 PROCESSING 与 startedTime 计算的展示/接管条件，不写入第五种状态。
     */
    public boolean isProcessingStale(InboxItem inboxItem) {
        if (inboxItem == null || inboxItem.getAiStatus() != AiProcessingStatus.PROCESSING) {
            return false;
        }
        LocalDateTime startedTime = inboxItem.getAiStartedTime();
        if (startedTime == null) {
            return true;
        }
        LocalDateTime staleBefore = LocalDateTime.now(clock).minus(processingStaleAfter);
        return !startedTime.isAfter(staleBefore);
    }

    private String normalizeErrorMessage(String errorMessage) {
        String candidate = errorMessage == null || errorMessage.isBlank()
                ? "AI 分析失败"
                : errorMessage;
        return candidate.length() <= MAX_ERROR_MESSAGE_CHARS
                ? candidate
                : candidate.substring(0, MAX_ERROR_MESSAGE_CHARS);
    }
}

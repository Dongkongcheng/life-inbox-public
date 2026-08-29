package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.RelationProcessingStatus;
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
import java.util.Optional;
import java.util.UUID;

/** Relation Discovery 的 Claim、失败收尾和 stale 判断；每次写入都是独立短事务。 */
@Service
public class RelationProcessingStatusService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final int MAX_ERROR_MESSAGE_CHARS = 255;

    private final InboxItemMapper inboxItemMapper;
    private final Duration processingStaleAfter;
    private final Clock clock;

    @Autowired
    public RelationProcessingStatusService(
            InboxItemMapper inboxItemMapper,
            @Value("${life-inbox.ai.processing-stale-after:5m}") Duration processingStaleAfter
    ) {
        this(inboxItemMapper, processingStaleAfter, Clock.systemDefaultZone());
    }

    RelationProcessingStatusService(
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

    /** 自动触发只领取 NOT_PROCESSED；已失败或已成功的条目不会产生隐式 Provider 调用。 */
    @Transactional
    public Optional<String> claimAutomatic(Long inboxItemId) {
        String attemptId = UUID.randomUUID().toString();
        int updatedRows = inboxItemMapper.markRelationAutomaticProcessing(
                inboxItemId,
                RelationProcessingStatus.NOT_PROCESSED,
                RelationProcessingStatus.PROCESSING,
                attemptId,
                LocalDateTime.now(clock)
        );
        return updatedRows == 1 ? Optional.of(attemptId) : Optional.empty();
    }

    /** 用户可重试 FAILED 或接管 stale PROCESSING；SUCCESS 与 fresh PROCESSING 都明确拒绝。 */
    @Transactional
    public String claimManual(Long inboxItemId) {
        String attemptId = UUID.randomUUID().toString();
        LocalDateTime startedTime = LocalDateTime.now(clock);
        int updatedRows = inboxItemMapper.markRelationManualProcessing(
                inboxItemId,
                RelationProcessingStatus.NOT_PROCESSED,
                RelationProcessingStatus.PROCESSING,
                RelationProcessingStatus.FAILED,
                attemptId,
                startedTime,
                startedTime.minus(processingStaleAfter)
        );
        if (updatedRows == 1) {
            return attemptId;
        }

        InboxItem current = inboxItemMapper.selectById(inboxItemId);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
        if (!STATUS_ACTIVE.equals(current.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "只有 ACTIVE InboxItem 可以发现 Relation"
            );
        }
        if (current.getRelationStatus() == RelationProcessingStatus.SUCCESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Relation Discovery 已完成");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Relation Discovery 正在进行中");
    }

    /**
     * Rediscovery 是 SUCCESS 的显式刷新动作；新 UUID 继续让既有 Attempt Guard 拒绝迟到结果。
     */
    @Transactional
    public String claimRediscovery(Long inboxItemId) {
        String attemptId = UUID.randomUUID().toString();
        int updatedRows = inboxItemMapper.markRelationRediscoveryProcessing(
                inboxItemId,
                RelationProcessingStatus.SUCCESS,
                RelationProcessingStatus.PROCESSING,
                attemptId,
                LocalDateTime.now(clock)
        );
        if (updatedRows == 1) {
            return attemptId;
        }

        InboxItem current = inboxItemMapper.selectById(inboxItemId);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
        if (!STATUS_ACTIVE.equals(current.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "只有 ACTIVE InboxItem 可以重新发现 Relation"
            );
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "只有已完成 Relation Discovery 的 InboxItem 可以重新发现"
        );
    }

    /** 失败不删除既有关系，只结束仍属于当前调用的 Attempt。 */
    @Transactional
    public boolean markFailed(Long inboxItemId, String attemptId, String errorMessage) {
        int updatedRows = inboxItemMapper.markRelationFailed(
                inboxItemId,
                attemptId,
                RelationProcessingStatus.PROCESSING,
                RelationProcessingStatus.FAILED,
                normalizeErrorMessage(errorMessage)
        );
        return updatedRows == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markAutoSchedulingFailed(Long inboxItemId, String errorMessage) {
        int updatedRows = inboxItemMapper.markRelationAutoSchedulingFailed(
                inboxItemId,
                RelationProcessingStatus.NOT_PROCESSED,
                RelationProcessingStatus.FAILED,
                normalizeErrorMessage(errorMessage)
        );
        return updatedRows == 1;
    }

    public boolean isProcessingStale(InboxItem inboxItem) {
        if (inboxItem == null
                || inboxItem.getRelationStatus() != RelationProcessingStatus.PROCESSING) {
            return false;
        }
        LocalDateTime startedTime = inboxItem.getRelationStartedTime();
        if (startedTime == null) {
            return true;
        }
        LocalDateTime staleBefore = LocalDateTime.now(clock).minus(processingStaleAfter);
        return !startedTime.isAfter(staleBefore);
    }

    private String normalizeErrorMessage(String errorMessage) {
        String candidate = errorMessage == null || errorMessage.isBlank()
                ? "Relation Discovery 失败"
                : errorMessage;
        return candidate.length() <= MAX_ERROR_MESSAGE_CHARS
                ? candidate
                : candidate.substring(0, MAX_ERROR_MESSAGE_CHARS);
    }
}

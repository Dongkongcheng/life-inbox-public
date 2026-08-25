package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.ActionProcessingStatus;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/** Action Extraction 的开始、失败与 stale 判断；每次写入都是独立短事务。 */
@Service
public class ActionProcessingStatusService {

    private static final int MAX_ERROR_MESSAGE_CHARS = 255;

    private final InboxItemMapper inboxItemMapper;
    private final Duration processingStaleAfter;
    private final Clock clock;

    @Autowired
    public ActionProcessingStatusService(
            InboxItemMapper inboxItemMapper,
            @Value("${life-inbox.ai.processing-stale-after:5m}") Duration processingStaleAfter
    ) {
        this(inboxItemMapper, processingStaleAfter, Clock.systemDefaultZone());
    }

    ActionProcessingStatusService(
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

    /** fresh PROCESSING 拒绝重复执行；stale PROCESSING 可由新的 UUID Attempt 接管。 */
    @Transactional
    public String markProcessing(Long inboxItemId) {
        String attemptId = UUID.randomUUID().toString();
        LocalDateTime startedTime = LocalDateTime.now(clock);
        int updatedRows = inboxItemMapper.markActionProcessing(
                inboxItemId,
                ActionProcessingStatus.PROCESSING,
                attemptId,
                startedTime,
                startedTime.minus(processingStaleAfter)
        );
        if (updatedRows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Action 提取正在进行中");
        }
        return attemptId;
    }

    /** 失败保留旧 Candidate，只结束仍属于当前调用的 Attempt。 */
    @Transactional
    public boolean markFailed(Long inboxItemId, String attemptId, String errorMessage) {
        int updatedRows = inboxItemMapper.markActionFailed(
                inboxItemId,
                attemptId,
                ActionProcessingStatus.PROCESSING,
                ActionProcessingStatus.FAILED,
                normalizeErrorMessage(errorMessage)
        );
        return updatedRows == 1;
    }

    public boolean isProcessingStale(InboxItem inboxItem) {
        if (inboxItem == null
                || inboxItem.getActionStatus() != ActionProcessingStatus.PROCESSING) {
            return false;
        }
        LocalDateTime startedTime = inboxItem.getActionStartedTime();
        if (startedTime == null) {
            return true;
        }
        LocalDateTime staleBefore = LocalDateTime.now(clock).minus(processingStaleAfter);
        return !startedTime.isAfter(staleBefore);
    }

    private String normalizeErrorMessage(String errorMessage) {
        String candidate = errorMessage == null || errorMessage.isBlank()
                ? "Action 提取失败"
                : errorMessage;
        return candidate.length() <= MAX_ERROR_MESSAGE_CHARS
                ? candidate
                : candidate.substring(0, MAX_ERROR_MESSAGE_CHARS);
    }
}

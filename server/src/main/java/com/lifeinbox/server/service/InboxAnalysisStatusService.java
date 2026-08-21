package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.AiProcessingStatus;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 管理 Analyze 开始与失败状态；每个方法都是独立、立即提交的短事务。 */
@Service
public class InboxAnalysisStatusService {

    private static final int MAX_ERROR_MESSAGE_CHARS = 255;

    private final InboxItemMapper inboxItemMapper;

    public InboxAnalysisStatusService(InboxItemMapper inboxItemMapper) {
        this.inboxItemMapper = inboxItemMapper;
    }

    /**
     * 在远程调用前提交 PROCESSING。条件 UPDATE 是数据库级重复请求保护，
     * 当前单机 MySQL 已足够，不需要 Redis 分布式锁。
     */
    @Transactional
    public void markProcessing(Long inboxItemId) {
        int updatedRows = inboxItemMapper.markAnalysisProcessing(
                inboxItemId,
                AiProcessingStatus.PROCESSING
        );
        if (updatedRows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI 分析正在进行中");
        }
    }

    /**
     * 失败只记录安全摘要并结束本次尝试；旧的五类 AI 结果继续保留供用户查看。
     */
    @Transactional
    public void markFailed(Long inboxItemId, String errorMessage) {
        String candidate = errorMessage == null || errorMessage.isBlank()
                ? "AI 分析失败"
                : errorMessage;
        String safeMessage = candidate.length() <= MAX_ERROR_MESSAGE_CHARS
                ? candidate
                : candidate.substring(0, MAX_ERROR_MESSAGE_CHARS);
        int updatedRows = inboxItemMapper.markAnalysisFailed(
                inboxItemId,
                AiProcessingStatus.PROCESSING,
                AiProcessingStatus.FAILED,
                safeMessage
        );
        if (updatedRows != 1) {
            throw new IllegalStateException("AI 失败状态保存失败");
        }
    }
}

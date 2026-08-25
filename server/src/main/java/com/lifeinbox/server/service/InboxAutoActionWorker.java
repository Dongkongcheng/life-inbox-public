package com.lifeinbox.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 后台入口复用同步手动提取的统一编排，异常只记录日志，绝不回传到 Capture。 */
@Service
public class InboxAutoActionWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxAutoActionWorker.class);

    private final ActionCandidateService actionCandidateService;

    public InboxAutoActionWorker(ActionCandidateService actionCandidateService) {
        this.actionCandidateService = actionCandidateService;
    }

    public void extract(Long inboxItemId) {
        try {
            actionCandidateService.extract(inboxItemId);
        } catch (RuntimeException exception) {
            // 统一 Service 已按 Attempt 安全收尾；Action 失败不影响 Capture、Analyze 或 Search。
            LOGGER.warn("InboxItem {} 的自动 Action 提取失败", inboxItemId, exception);
        }
    }
}

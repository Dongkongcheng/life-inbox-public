package com.lifeinbox.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 后台线程只调用现有统一 Analyze；状态领取、失败记录和 Attempt Guard 仍由原服务负责。
 */
@Service
public class InboxAutoAnalyzeWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxAutoAnalyzeWorker.class);

    private final InboxAnalyzeService inboxAnalyzeService;

    public InboxAutoAnalyzeWorker(InboxAnalyzeService inboxAnalyzeService) {
        this.inboxAnalyzeService = inboxAnalyzeService;
    }

    public void analyze(Long inboxItemId) {
        try {
            inboxAnalyzeService.analyze(inboxItemId);
        } catch (RuntimeException exception) {
            // Analyze Service 已按 Attempt 写入安全失败状态；后台线程不能把异常传播回 Capture。
            LOGGER.warn("InboxItem {} 的自动 AI 分析失败", inboxItemId, exception);
        }
    }
}

package com.lifeinbox.server.service;

import com.lifeinbox.server.config.AiBackgroundConfiguration;
import com.lifeinbox.server.event.InboxItemCapturedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.RejectedExecutionException;

/**
 * Capture 提交后只负责投递后台任务；真实 Analyze 不占用数据库事务和请求线程。
 */
@Component
public class InboxAutoAnalyzeListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxAutoAnalyzeListener.class);
    private static final String SCHEDULING_FAILED_MESSAGE = "自动分析队列繁忙，请手动重试";

    private final TaskExecutor taskExecutor;
    private final InboxAutoAnalyzeWorker worker;
    private final InboxAnalysisStatusService statusService;
    private final boolean enabled;

    public InboxAutoAnalyzeListener(
            @Qualifier(AiBackgroundConfiguration.AI_TASK_EXECUTOR) TaskExecutor taskExecutor,
            InboxAutoAnalyzeWorker worker,
            InboxAnalysisStatusService statusService,
            @Value("${life-inbox.ai.auto-analyze-enabled:false}") boolean enabled
    ) {
        this.taskExecutor = taskExecutor;
        this.worker = worker;
        this.statusService = statusService;
        this.enabled = enabled;
    }

    /**
     * AFTER_COMMIT 保证用户先拿到已提交的 Capture；队列满也不能把成功请求变成失败响应。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInboxItemCaptured(InboxItemCapturedEvent event) {
        if (!enabled) {
            return;
        }

        try {
            taskExecutor.execute(() -> worker.analyze(event.inboxItemId()));
        } catch (RejectedExecutionException exception) {
            LOGGER.warn("自动 AI 分析队列已满，InboxItem={}", event.inboxItemId(), exception);
            try {
                statusService.markAutoSchedulingFailed(
                        event.inboxItemId(),
                        SCHEDULING_FAILED_MESSAGE
                );
            } catch (RuntimeException statusException) {
                // Capture 已提交；状态补写失败只能记录日志，不能向已完成的请求传播。
                exception.addSuppressed(statusException);
                LOGGER.warn(
                        "InboxItem {} 的自动分析排队失败状态保存失败",
                        event.inboxItemId(),
                        statusException
                );
            }
        }
    }
}

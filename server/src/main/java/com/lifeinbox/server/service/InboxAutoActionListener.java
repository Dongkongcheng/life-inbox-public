package com.lifeinbox.server.service;

import com.lifeinbox.server.config.AiBackgroundConfiguration;
import com.lifeinbox.server.event.InboxActionContentReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.RejectedExecutionException;

/** 可用正文提交后把 Action Extraction 投递到既有有界后台线程池。 */
@Component
public class InboxAutoActionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxAutoActionListener.class);

    private final TaskExecutor taskExecutor;
    private final InboxAutoActionWorker worker;
    private final boolean enabled;

    public InboxAutoActionListener(
            @Qualifier(AiBackgroundConfiguration.AI_TASK_EXECUTOR) TaskExecutor taskExecutor,
            InboxAutoActionWorker worker,
            @Value("${life-inbox.action.auto-extract-enabled:true}") boolean enabled
    ) {
        this.taskExecutor = taskExecutor;
        this.worker = worker;
        this.enabled = enabled;
    }

    /**
     * AFTER_COMMIT 保证 TEXT Capture 或派生正文先成为业务事实；队列满发生在 Claim 之前，
     * 因此状态保持 NOT_PROCESSED/原状态并允许用户手动重试。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentReady(InboxActionContentReadyEvent event) {
        if (!enabled) {
            return;
        }
        try {
            taskExecutor.execute(() -> worker.extract(event.inboxItemId()));
        } catch (RejectedExecutionException exception) {
            LOGGER.warn("自动 Action 提取队列已满，InboxItem={}", event.inboxItemId(), exception);
        }
    }
}

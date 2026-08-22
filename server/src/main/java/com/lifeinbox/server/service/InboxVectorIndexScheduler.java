package com.lifeinbox.server.service;

import com.lifeinbox.server.config.AiBackgroundConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/** 复用现有有界后台执行器投递 Vector 任务，不创建第二套队列或状态机。 */
@Service
public class InboxVectorIndexScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxVectorIndexScheduler.class);

    private final TaskExecutor taskExecutor;
    private final InboxVectorIndexWorker worker;

    public InboxVectorIndexScheduler(
            @Qualifier(AiBackgroundConfiguration.AI_TASK_EXECUTOR) TaskExecutor taskExecutor,
            InboxVectorIndexWorker worker
    ) {
        this.taskExecutor = taskExecutor;
        this.worker = worker;
    }

    public void scheduleIndex(Long inboxItemId, String expectedAttemptId) {
        schedule(inboxItemId, () -> worker.index(inboxItemId, expectedAttemptId), "Index");
    }

    public void scheduleDelete(Long inboxItemId) {
        schedule(inboxItemId, () -> worker.delete(inboxItemId), "Delete");
    }

    private void schedule(Long inboxItemId, Runnable task, String operation) {
        try {
            taskExecutor.execute(task);
        } catch (RuntimeException exception) {
            // 没有持久队列时允许本次增强任务丢失；业务数据仍可在未来单独 Re-index。
            LOGGER.warn(
                    "Vector {} 后台队列已满，InboxItem={}",
                    operation,
                    inboxItemId,
                    exception
            );
        }
    }
}

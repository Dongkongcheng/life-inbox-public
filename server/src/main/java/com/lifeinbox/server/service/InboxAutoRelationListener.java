package com.lifeinbox.server.service;

import com.lifeinbox.server.config.AiBackgroundConfiguration;
import com.lifeinbox.server.event.InboxVectorReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/** Vector 真正就绪后，把首次 Relation Discovery 投递到既有有界 AI 线程池。 */
@Component
public class InboxAutoRelationListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxAutoRelationListener.class);
    private static final String SCHEDULING_FAILED_MESSAGE = "自动 Relation 队列繁忙，请手动重试";

    private final TaskExecutor taskExecutor;
    private final InboxAutoRelationWorker worker;
    private final RelationProcessingStatusService statusService;
    private final boolean enabled;

    public InboxAutoRelationListener(
            @Qualifier(AiBackgroundConfiguration.AI_TASK_EXECUTOR) TaskExecutor taskExecutor,
            InboxAutoRelationWorker worker,
            RelationProcessingStatusService statusService,
            @Value("${life-inbox.relation.auto-discover-enabled:true}") boolean enabled
    ) {
        this.taskExecutor = taskExecutor;
        this.worker = worker;
        this.statusService = statusService;
        this.enabled = enabled;
    }

    @EventListener
    public void onVectorReady(InboxVectorReadyEvent event) {
        if (!enabled) {
            return;
        }
        try {
            taskExecutor.execute(() -> worker.discover(event.inboxItemId()));
        } catch (RuntimeException exception) {
            LOGGER.warn("自动 Relation Discovery 队列已满，InboxItem={}", event.inboxItemId(), exception);
            try {
                statusService.markAutoSchedulingFailed(
                        event.inboxItemId(),
                        SCHEDULING_FAILED_MESSAGE
                );
            } catch (RuntimeException statusException) {
                exception.addSuppressed(statusException);
                LOGGER.warn(
                        "InboxItem {} 的自动 Relation 排队失败状态保存失败",
                        event.inboxItemId(),
                        statusException
                );
            }
        }
    }
}

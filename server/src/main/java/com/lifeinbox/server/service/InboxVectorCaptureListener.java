package com.lifeinbox.server.service;

import com.lifeinbox.server.event.InboxItemCapturedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Capture 提交后让已有正文自然进入索引；没有 Searchable Content 的类型会安全跳过。 */
@Component
public class InboxVectorCaptureListener {

    private final InboxVectorIndexScheduler scheduler;

    public InboxVectorCaptureListener(InboxVectorIndexScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInboxItemCaptured(InboxItemCapturedEvent event) {
        scheduler.scheduleIndex(event.inboxItemId(), null);
    }
}

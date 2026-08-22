package com.lifeinbox.server.service;

import com.lifeinbox.server.event.InboxItemCapturedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InboxVectorCaptureListenerTests {

    @Test
    void captureAfterCommitSchedulesCurrentSourceContent() {
        InboxVectorIndexScheduler scheduler = mock(InboxVectorIndexScheduler.class);
        InboxVectorCaptureListener listener = new InboxVectorCaptureListener(scheduler);

        listener.onInboxItemCaptured(new InboxItemCapturedEvent(123L));

        verify(scheduler).scheduleIndex(123L, null);
    }
}

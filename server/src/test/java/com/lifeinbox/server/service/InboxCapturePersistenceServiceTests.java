package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.event.InboxItemCapturedEvent;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboxCapturePersistenceServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final InboxCapturePersistenceService persistenceService =
            new InboxCapturePersistenceService(inboxItemMapper, eventPublisher);

    @Test
    void savePublishesOnlyThePersistedInboxItemId() {
        InboxItem item = new InboxItem();
        item.setId(42L);
        item.setType("TEXT");
        when(inboxItemMapper.insert(item)).thenReturn(1);
        when(inboxItemMapper.selectById(42L)).thenReturn(item);

        assertEquals(item, persistenceService.save(item));

        ArgumentCaptor<InboxItemCapturedEvent> eventCaptor =
                ArgumentCaptor.forClass(InboxItemCapturedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(42L, eventCaptor.getValue().inboxItemId());
    }

    @Test
    void insertFailureDoesNotPublishCaptureEvent() {
        InboxItem item = new InboxItem();
        when(inboxItemMapper.insert(item)).thenReturn(0);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> persistenceService.save(item)
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void missingInsertedRowDoesNotPublishCaptureEvent() {
        InboxItem item = new InboxItem();
        item.setId(42L);
        when(inboxItemMapper.insert(item)).thenReturn(1);
        when(inboxItemMapper.selectById(42L)).thenReturn(null);

        assertThrows(ResponseStatusException.class, () -> persistenceService.save(item));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void saveDefinesTheShortCaptureTransaction() throws NoSuchMethodException {
        Method save = InboxCapturePersistenceService.class.getMethod("save", InboxItem.class);

        assertTrue(save.isAnnotationPresent(Transactional.class));
    }
}

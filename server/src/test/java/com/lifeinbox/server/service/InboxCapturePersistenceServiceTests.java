package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.event.InboxActionContentReadyEvent;
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
import static org.mockito.Mockito.times;

class InboxCapturePersistenceServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final InboxCapturePersistenceService persistenceService =
            new InboxCapturePersistenceService(inboxItemMapper, eventPublisher);

    @Test
    void textSavePublishesCaptureAndActionContentReadyInsideTransaction() {
        InboxItem item = new InboxItem();
        item.setId(42L);
        item.setType("TEXT");
        when(inboxItemMapper.insert(item)).thenReturn(1);
        when(inboxItemMapper.selectById(42L)).thenReturn(item);

        assertEquals(item, persistenceService.save(item));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        assertEquals(
                java.util.List.of(
                        new InboxItemCapturedEvent(42L),
                        new InboxActionContentReadyEvent(42L)
                ),
                eventCaptor.getAllValues()
        );
    }

    @Test
    void urlCaptureDoesNotPublishActionReadyBeforeExtractedContentExists() {
        InboxItem item = new InboxItem();
        item.setId(43L);
        item.setType("URL");
        when(inboxItemMapper.insert(item)).thenReturn(1);
        when(inboxItemMapper.selectById(43L)).thenReturn(item);

        persistenceService.save(item);

        verify(eventPublisher).publishEvent(new InboxItemCapturedEvent(43L));
        verify(eventPublisher, never()).publishEvent(any(InboxActionContentReadyEvent.class));
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

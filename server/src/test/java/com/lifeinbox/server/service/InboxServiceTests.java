package com.lifeinbox.server.service;

import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboxServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final InboxService inboxService = new InboxService(inboxItemMapper);

    @Test
    void deleteRemovesExistingItem() {
        when(inboxItemMapper.deleteById(1L)).thenReturn(1);

        assertDoesNotThrow(() -> inboxService.delete(1L));

        verify(inboxItemMapper).deleteById(1L);
    }

    @Test
    void deleteReturnsNotFoundWhenItemDoesNotExist() {
        when(inboxItemMapper.deleteById(99L)).thenReturn(0);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.delete(99L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }
}

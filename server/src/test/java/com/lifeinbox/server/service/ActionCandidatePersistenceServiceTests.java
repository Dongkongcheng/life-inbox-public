package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.ActionCandidate;
import com.lifeinbox.server.entity.ActionCandidateStatus;
import com.lifeinbox.server.entity.ActionCandidateType;
import com.lifeinbox.server.mapper.ActionCandidateMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionCandidatePersistenceServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final ActionCandidateMapper actionCandidateMapper = mock(ActionCandidateMapper.class);
    private final ActionCandidatePersistenceService service =
            new ActionCandidatePersistenceService(inboxItemMapper, actionCandidateMapper);

    @Test
    void successfulReplacementLocksSourceDeletesPendingAndInsertsEveryCandidate() {
        when(inboxItemMapper.selectActiveIdForUpdate(1L)).thenReturn(1L);
        when(actionCandidateMapper.insert(any(ActionCandidate.class))).thenReturn(1);
        when(actionCandidateMapper.selectByInboxItemId(1L)).thenReturn(List.of());
        List<ValidatedActionCandidate> candidates = List.of(
                new ValidatedActionCandidate(
                        ActionCandidateType.DEADLINE,
                        "提交报告",
                        "8月25日前",
                        LocalDate.of(2026, 8, 25),
                        "8月25日前提交报告"
                ),
                new ValidatedActionCandidate(
                        ActionCandidateType.TODO,
                        "整理参考文献",
                        null,
                        null,
                        "整理参考文献"
                )
        );

        service.replacePending(1L, candidates);

        ArgumentCaptor<ActionCandidate> inserted = ArgumentCaptor.forClass(ActionCandidate.class);
        verify(actionCandidateMapper, org.mockito.Mockito.times(2)).insert(inserted.capture());
        assertEquals(ActionCandidateType.DEADLINE, inserted.getAllValues().getFirst().getActionType());
        assertEquals(LocalDate.of(2026, 8, 25), inserted.getAllValues().getFirst().getDeadlineDate());
        assertEquals(ActionCandidateStatus.PENDING, inserted.getAllValues().getFirst().getStatus());
        assertEquals(ActionCandidateStatus.PENDING, inserted.getAllValues().get(1).getStatus());

        InOrder order = inOrder(inboxItemMapper, actionCandidateMapper);
        order.verify(inboxItemMapper).selectActiveIdForUpdate(1L);
        order.verify(actionCandidateMapper).deletePendingByInboxItemId(1L);
        order.verify(actionCandidateMapper, org.mockito.Mockito.times(2)).insert(any(ActionCandidate.class));
        order.verify(actionCandidateMapper).selectByInboxItemId(1L);
    }

    @Test
    void successfulNoActionDeletesOldPendingWithoutInserting() {
        when(inboxItemMapper.selectActiveIdForUpdate(2L)).thenReturn(2L);
        when(actionCandidateMapper.selectByInboxItemId(2L)).thenReturn(List.of());

        assertEquals(List.of(), service.replacePending(2L, List.of()));

        verify(actionCandidateMapper).deletePendingByInboxItemId(2L);
        verify(actionCandidateMapper, never()).insert(any(ActionCandidate.class));
    }

    @Test
    void sourceArchivedDuringAiCallStopsBeforeDeletingPending() {
        when(inboxItemMapper.selectActiveIdForUpdate(3L)).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.replacePending(3L, List.of())
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(actionCandidateMapper, never()).deletePendingByInboxItemId(anyLong());
    }

    @Test
    void insertFailureEscapesAndSkipsReload() {
        when(inboxItemMapper.selectActiveIdForUpdate(4L)).thenReturn(4L);
        when(actionCandidateMapper.insert(any(ActionCandidate.class)))
                .thenThrow(new IllegalStateException("mock insert failure"));

        assertThrows(
                IllegalStateException.class,
                () -> service.replacePending(4L, List.of(new ValidatedActionCandidate(
                        ActionCandidateType.TODO, "整理资料", null, null, "整理资料"
                )))
        );

        verify(actionCandidateMapper).deletePendingByInboxItemId(4L);
        verify(actionCandidateMapper, never()).selectByInboxItemId(anyLong());
    }

    @Test
    void replacePendingDefinesSpringTransactionBoundary() throws NoSuchMethodException {
        Method method = ActionCandidatePersistenceService.class.getMethod(
                "replacePending",
                Long.class,
                List.class
        );

        assertTrue(method.isAnnotationPresent(Transactional.class));
    }
}

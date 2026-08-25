package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.ActionCandidate;
import com.lifeinbox.server.entity.ActionCandidateStatus;
import com.lifeinbox.server.entity.ActionCandidateType;
import com.lifeinbox.server.entity.ActionProcessingStatus;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionCandidatePersistenceServiceTests {

    private static final String ATTEMPT_ID = "attempt-current";

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final ActionCandidateMapper actionCandidateMapper = mock(ActionCandidateMapper.class);
    private final ActionCandidatePersistenceService service =
            new ActionCandidatePersistenceService(inboxItemMapper, actionCandidateMapper);

    @Test
    void successfulReplacementOwnsAttemptReplacesPendingAndMarksSuccessLast() {
        allowCurrentAttempt(1L);
        when(actionCandidateMapper.selectByInboxItemIdForUpdate(1L)).thenReturn(List.of());
        when(actionCandidateMapper.insert(any(ActionCandidate.class))).thenReturn(1);
        when(inboxItemMapper.markActionSuccess(
                1L,
                ATTEMPT_ID,
                ActionProcessingStatus.PROCESSING,
                ActionProcessingStatus.SUCCESS
        )).thenReturn(1);
        when(actionCandidateMapper.selectByInboxItemId(1L)).thenReturn(List.of());
        List<ValidatedActionCandidate> candidates = List.of(
                deadline("提交报告", "明天", LocalDate.of(2026, 8, 25)),
                todo("整理参考文献"),
                deadline("确认模糊截止时间", "月底左右", null)
        );

        service.completeSuccess(1L, ATTEMPT_ID, candidates);

        ArgumentCaptor<ActionCandidate> inserted = ArgumentCaptor.forClass(ActionCandidate.class);
        verify(actionCandidateMapper, times(3)).insert(inserted.capture());
        assertEquals(ActionCandidateType.DEADLINE, inserted.getAllValues().getFirst().getActionType());
        assertEquals(LocalDate.of(2026, 8, 25), inserted.getAllValues().getFirst().getDeadlineDate());
        assertEquals(ActionCandidateStatus.PENDING, inserted.getAllValues().get(1).getStatus());
        assertNull(inserted.getAllValues().get(2).getDeadlineDate());

        InOrder order = inOrder(inboxItemMapper, actionCandidateMapper);
        order.verify(inboxItemMapper).selectCurrentActionAttemptForUpdate(
                1L,
                ATTEMPT_ID,
                ActionProcessingStatus.PROCESSING
        );
        order.verify(actionCandidateMapper).selectByInboxItemIdForUpdate(1L);
        order.verify(actionCandidateMapper).deletePendingByInboxItemId(1L);
        order.verify(actionCandidateMapper, times(3)).insert(any(ActionCandidate.class));
        order.verify(inboxItemMapper).markActionSuccess(
                1L,
                ATTEMPT_ID,
                ActionProcessingStatus.PROCESSING,
                ActionProcessingStatus.SUCCESS
        );
    }

    @Test
    void successfulNoActionDeletesPendingAndStillMarksSuccess() {
        allowCurrentAttempt(2L);
        when(actionCandidateMapper.selectByInboxItemIdForUpdate(2L)).thenReturn(List.of());
        when(inboxItemMapper.markActionSuccess(
                2L, ATTEMPT_ID, ActionProcessingStatus.PROCESSING, ActionProcessingStatus.SUCCESS
        )).thenReturn(1);
        when(actionCandidateMapper.selectByInboxItemId(2L)).thenReturn(List.of());

        assertEquals(List.of(), service.completeSuccess(2L, ATTEMPT_ID, List.of()));

        verify(actionCandidateMapper).deletePendingByInboxItemId(2L);
        verify(actionCandidateMapper, never()).insert(any(ActionCandidate.class));
        verify(inboxItemMapper).markActionSuccess(
                2L, ATTEMPT_ID, ActionProcessingStatus.PROCESSING, ActionProcessingStatus.SUCCESS
        );
    }

    @Test
    void expiredAttemptCannotMutateCandidatesOrStatus() {
        when(inboxItemMapper.selectCurrentActionAttemptForUpdate(
                3L,
                ATTEMPT_ID,
                ActionProcessingStatus.PROCESSING
        )).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.completeSuccess(3L, ATTEMPT_ID, List.of())
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(actionCandidateMapper, never()).deletePendingByInboxItemId(anyLong());
        verify(inboxItemMapper, never()).markActionSuccess(
                anyLong(), any(), any(), any()
        );
    }

    @Test
    void insertFailureEscapesBeforeSuccessSoTransactionCanRollbackEverything() {
        allowCurrentAttempt(4L);
        when(actionCandidateMapper.selectByInboxItemIdForUpdate(4L)).thenReturn(List.of());
        when(actionCandidateMapper.insert(any(ActionCandidate.class)))
                .thenThrow(new IllegalStateException("mock insert failure"));

        assertThrows(
                IllegalStateException.class,
                () -> service.completeSuccess(4L, ATTEMPT_ID, List.of(todo("整理资料")))
        );

        verify(actionCandidateMapper).deletePendingByInboxItemId(4L);
        verify(inboxItemMapper, never()).markActionSuccess(anyLong(), any(), any(), any());
    }

    @Test
    void exactAcceptedAndDismissedDuplicatesAreSuppressedButDifferentCandidateRemains() {
        allowCurrentAttempt(5L);
        when(actionCandidateMapper.selectByInboxItemIdForUpdate(5L)).thenReturn(List.of(
                terminal(ActionCandidateStatus.ACCEPTED, ActionCandidateType.TODO,
                        "提交　报告", null, null),
                terminal(ActionCandidateStatus.DISMISSED, ActionCandidateType.DEADLINE,
                        "确认截止时间", "月底左右", null)
        ));
        when(actionCandidateMapper.insert(any(ActionCandidate.class))).thenReturn(1);
        when(inboxItemMapper.markActionSuccess(
                5L, ATTEMPT_ID, ActionProcessingStatus.PROCESSING, ActionProcessingStatus.SUCCESS
        )).thenReturn(1);
        when(actionCandidateMapper.selectByInboxItemId(5L)).thenReturn(List.of());

        service.completeSuccess(5L, ATTEMPT_ID, List.of(
                todo("提交 报告"),
                deadline("确认截止时间", "月底左右", null),
                todo("准备答辩 PPT")
        ));

        ArgumentCaptor<ActionCandidate> inserted = ArgumentCaptor.forClass(ActionCandidate.class);
        verify(actionCandidateMapper).insert(inserted.capture());
        assertEquals("准备答辩 PPT", inserted.getValue().getTitle());
        verify(actionCandidateMapper).deletePendingByInboxItemId(5L);
    }

    @Test
    void completeSuccessDefinesSpringTransactionBoundary() throws NoSuchMethodException {
        Method method = ActionCandidatePersistenceService.class.getMethod(
                "completeSuccess",
                Long.class,
                String.class,
                List.class
        );

        assertTrue(method.isAnnotationPresent(Transactional.class));
    }

    private void allowCurrentAttempt(Long inboxItemId) {
        when(inboxItemMapper.selectCurrentActionAttemptForUpdate(
                inboxItemId,
                ATTEMPT_ID,
                ActionProcessingStatus.PROCESSING
        )).thenReturn(inboxItemId);
    }

    private ValidatedActionCandidate todo(String title) {
        return new ValidatedActionCandidate(ActionCandidateType.TODO, title, null, null, title);
    }

    private ValidatedActionCandidate deadline(String title, String text, LocalDate date) {
        return new ValidatedActionCandidate(ActionCandidateType.DEADLINE, title, text, date, title);
    }

    private ActionCandidate terminal(
            ActionCandidateStatus status,
            ActionCandidateType type,
            String title,
            String deadlineText,
            LocalDate deadline
    ) {
        ActionCandidate candidate = new ActionCandidate();
        candidate.setActionType(type);
        candidate.setTitle(title);
        candidate.setDeadlineText(deadlineText);
        candidate.setDeadlineDate(deadline);
        candidate.setStatus(status);
        return candidate;
    }
}

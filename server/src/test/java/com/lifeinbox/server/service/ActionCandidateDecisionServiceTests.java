package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.ActionCandidateAcceptanceResponse;
import com.lifeinbox.server.dto.ActionCandidateResponse;
import com.lifeinbox.server.entity.ActionCandidate;
import com.lifeinbox.server.entity.ActionCandidateStatus;
import com.lifeinbox.server.entity.ActionCandidateType;
import com.lifeinbox.server.entity.Todo;
import com.lifeinbox.server.entity.TodoStatus;
import com.lifeinbox.server.mapper.ActionCandidateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ActionCandidateDecisionServiceTests {

    private final ActionCandidateMapper actionCandidateMapper = mock(ActionCandidateMapper.class);
    private final TodoService todoService = mock(TodoService.class);
    private final ActionCandidateDecisionService service =
            new ActionCandidateDecisionService(actionCandidateMapper, todoService);

    @Test
    void acceptingPendingTodoCopiesOnlyBusinessFieldsAndMarksAccepted() {
        ActionCandidate pending = candidate(
                ActionCandidateStatus.PENDING,
                ActionCandidateType.TODO,
                "整理 Java 面试题",
                null,
                null,
                "这是提取证据"
        );
        Todo todo = todo(200L, "整理 Java 面试题", null);
        stubPendingAccept(pending, todo);

        ActionCandidateAcceptanceResponse response = service.accept(100L, 1L);

        verify(todoService).create("整理 Java 面试题", null, null, 100L, 1L);
        verify(actionCandidateMapper).markPendingAccepted(1L);
        assertEquals(ActionCandidateStatus.ACCEPTED, response.candidate().status());
        assertEquals(200L, response.todo().id());
        assertEquals(TodoStatus.OPEN, response.todo().status());
        assertNull(response.todo().dueDate());
        assertNull(todo.getDescription());
        assertNull(todo.getCompletedTime());
    }

    @Test
    void acceptingResolvedDeadlineCopiesDeadlineDateToTodoDueDate() {
        LocalDate deadline = LocalDate.of(2026, 8, 25);
        ActionCandidate pending = candidate(
                ActionCandidateStatus.PENDING,
                ActionCandidateType.DEADLINE,
                "提交课程设计报告",
                "8月25日前",
                deadline,
                "8月25日前提交课程设计报告"
        );
        Todo todo = todo(201L, pending.getTitle(), deadline);
        stubPendingAccept(pending, todo);

        ActionCandidateAcceptanceResponse response = service.accept(100L, 1L);

        verify(todoService).create(pending.getTitle(), null, deadline, 100L, 1L);
        assertEquals(deadline, response.todo().dueDate());
    }

    @Test
    void acceptingUnresolvedDeadlineCreatesTodoWithoutDueDate() {
        ActionCandidate pending = candidate(
                ActionCandidateStatus.PENDING,
                ActionCandidateType.DEADLINE,
                "确认截止时间",
                "月底左右",
                null,
                "月底左右确认截止时间"
        );
        Todo todo = todo(202L, pending.getTitle(), null);
        stubPendingAccept(pending, todo);

        ActionCandidateAcceptanceResponse response = service.accept(100L, 1L);

        verify(todoService).create(pending.getTitle(), null, null, 100L, 1L);
        assertNull(response.todo().dueDate());
    }

    @Test
    void serializedRepeatedAcceptReturnsSameTodoAndCreatesOnlyOnce() {
        ActionCandidate pending = candidate(
                ActionCandidateStatus.PENDING,
                ActionCandidateType.TODO,
                "整理资料",
                null,
                null,
                "整理资料"
        );
        ActionCandidate accepted = copyWithStatus(pending, ActionCandidateStatus.ACCEPTED);
        Todo existing = todo(203L, pending.getTitle(), null);
        when(actionCandidateMapper.selectByInboxItemIdAndIdForUpdate(100L, 1L))
                .thenReturn(pending, accepted);
        when(todoService.findBySourceActionCandidateId(1L)).thenReturn(null, existing);
        when(todoService.create(pending.getTitle(), null, null, 100L, 1L)).thenReturn(existing);
        when(actionCandidateMapper.markPendingAccepted(1L)).thenReturn(1);
        when(actionCandidateMapper.selectById(1L)).thenReturn(accepted);

        ActionCandidateAcceptanceResponse first = service.accept(100L, 1L);
        ActionCandidateAcceptanceResponse second = service.accept(100L, 1L);

        assertEquals(203L, first.todo().id());
        assertEquals(first.todo(), second.todo());
        verify(todoService, times(1)).create(pending.getTitle(), null, null, 100L, 1L);
        verify(actionCandidateMapper, times(1)).markPendingAccepted(1L);
    }

    @Test
    void acceptingDismissedCandidateIsConflict() {
        when(actionCandidateMapper.selectByInboxItemIdAndIdForUpdate(100L, 1L))
                .thenReturn(candidate(
                        ActionCandidateStatus.DISMISSED,
                        ActionCandidateType.TODO,
                        "整理资料",
                        null,
                        null,
                        "整理资料"
                ));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.accept(100L, 1L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verifyNoInteractions(todoService);
        verify(actionCandidateMapper, never()).markPendingAccepted(1L);
    }

    @Test
    void acceptedCandidateWithoutTodoIsControlledIntegrityFailure() {
        when(actionCandidateMapper.selectByInboxItemIdAndIdForUpdate(100L, 1L))
                .thenReturn(candidate(
                        ActionCandidateStatus.ACCEPTED,
                        ActionCandidateType.TODO,
                        "整理资料",
                        null,
                        null,
                        "整理资料"
                ));
        when(todoService.findBySourceActionCandidateId(1L)).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.accept(100L, 1L)
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
        verify(todoService, never()).create("整理资料", null, null, 100L, 1L);
    }

    @Test
    void candidateUpdateFailureEscapesSoTransactionCanRollbackTodo() {
        ActionCandidate pending = candidate(
                ActionCandidateStatus.PENDING,
                ActionCandidateType.TODO,
                "整理资料",
                null,
                null,
                "整理资料"
        );
        Todo todo = todo(204L, pending.getTitle(), null);
        when(actionCandidateMapper.selectByInboxItemIdAndIdForUpdate(100L, 1L))
                .thenReturn(pending);
        when(todoService.findBySourceActionCandidateId(1L)).thenReturn(null);
        when(todoService.create(pending.getTitle(), null, null, 100L, 1L)).thenReturn(todo);
        when(actionCandidateMapper.markPendingAccepted(1L)).thenReturn(0);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.accept(100L, 1L)
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
        verify(actionCandidateMapper, never()).selectById(1L);
    }

    @Test
    void dismissPendingUpdatesStatusWithoutCreatingOrDeletingTodo() {
        ActionCandidate pending = candidate(
                ActionCandidateStatus.PENDING,
                ActionCandidateType.TODO,
                "整理资料",
                null,
                null,
                "整理资料"
        );
        ActionCandidate dismissed = copyWithStatus(pending, ActionCandidateStatus.DISMISSED);
        when(actionCandidateMapper.selectByInboxItemIdAndIdForUpdate(100L, 1L))
                .thenReturn(pending);
        when(actionCandidateMapper.markPendingDismissed(1L)).thenReturn(1);
        when(actionCandidateMapper.selectById(1L)).thenReturn(dismissed);

        ActionCandidateResponse response = service.dismiss(100L, 1L);

        assertEquals(ActionCandidateStatus.DISMISSED, response.status());
        verify(actionCandidateMapper).markPendingDismissed(1L);
        verifyNoInteractions(todoService);
    }

    @Test
    void repeatedDismissIsIdempotent() {
        ActionCandidate dismissed = candidate(
                ActionCandidateStatus.DISMISSED,
                ActionCandidateType.TODO,
                "整理资料",
                null,
                null,
                "整理资料"
        );
        when(actionCandidateMapper.selectByInboxItemIdAndIdForUpdate(100L, 1L))
                .thenReturn(dismissed);

        ActionCandidateResponse response = service.dismiss(100L, 1L);

        assertEquals(ActionCandidateStatus.DISMISSED, response.status());
        verify(actionCandidateMapper, never()).markPendingDismissed(1L);
        verifyNoInteractions(todoService);
    }

    @Test
    void dismissingAcceptedCandidateIsConflictAndDoesNotTouchTodo() {
        when(actionCandidateMapper.selectByInboxItemIdAndIdForUpdate(100L, 1L))
                .thenReturn(candidate(
                        ActionCandidateStatus.ACCEPTED,
                        ActionCandidateType.TODO,
                        "整理资料",
                        null,
                        null,
                        "整理资料"
                ));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.dismiss(100L, 1L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(actionCandidateMapper, never()).markPendingDismissed(1L);
        verifyNoInteractions(todoService);
    }

    @Test
    void acceptAndDismissMissingCandidateReturnNotFound() {
        when(actionCandidateMapper.selectByInboxItemIdAndIdForUpdate(100L, 99L))
                .thenReturn(null);

        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.accept(100L, 99L))
                        .getStatusCode()
        );
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.dismiss(100L, 99L))
                        .getStatusCode()
        );
        verifyNoInteractions(todoService);
    }

    @Test
    void acceptAndDismissDefineSpringTransactionBoundaries() throws NoSuchMethodException {
        Method accept = ActionCandidateDecisionService.class.getMethod(
                "accept",
                Long.class,
                Long.class
        );
        Method dismiss = ActionCandidateDecisionService.class.getMethod(
                "dismiss",
                Long.class,
                Long.class
        );

        assertTrue(accept.isAnnotationPresent(Transactional.class));
        assertTrue(dismiss.isAnnotationPresent(Transactional.class));
    }

    private void stubPendingAccept(ActionCandidate pending, Todo todo) {
        ActionCandidate accepted = copyWithStatus(pending, ActionCandidateStatus.ACCEPTED);
        when(actionCandidateMapper.selectByInboxItemIdAndIdForUpdate(100L, 1L))
                .thenReturn(pending);
        when(todoService.findBySourceActionCandidateId(1L)).thenReturn(null);
        when(todoService.create(
                pending.getTitle(),
                null,
                pending.getDeadlineDate(),
                pending.getInboxItemId(),
                pending.getId()
        )).thenReturn(todo);
        when(actionCandidateMapper.markPendingAccepted(1L)).thenReturn(1);
        when(actionCandidateMapper.selectById(1L)).thenReturn(accepted);
    }

    private ActionCandidate candidate(
            ActionCandidateStatus status,
            ActionCandidateType type,
            String title,
            String deadlineText,
            LocalDate deadlineDate,
            String evidence
    ) {
        ActionCandidate candidate = new ActionCandidate();
        candidate.setId(1L);
        candidate.setInboxItemId(100L);
        candidate.setStatus(status);
        candidate.setActionType(type);
        candidate.setTitle(title);
        candidate.setDeadlineText(deadlineText);
        candidate.setDeadlineDate(deadlineDate);
        candidate.setEvidence(evidence);
        candidate.setCreatedTime(LocalDateTime.of(2026, 8, 24, 10, 0));
        candidate.setUpdatedTime(LocalDateTime.of(2026, 8, 24, 10, 0));
        return candidate;
    }

    private ActionCandidate copyWithStatus(
            ActionCandidate source,
            ActionCandidateStatus status
    ) {
        ActionCandidate copy = candidate(
                status,
                source.getActionType(),
                source.getTitle(),
                source.getDeadlineText(),
                source.getDeadlineDate(),
                source.getEvidence()
        );
        copy.setId(source.getId());
        copy.setInboxItemId(source.getInboxItemId());
        return copy;
    }

    private Todo todo(Long id, String title, LocalDate dueDate) {
        Todo todo = new Todo();
        todo.setId(id);
        todo.setSourceInboxItemId(100L);
        todo.setSourceActionCandidateId(1L);
        todo.setTitle(title);
        todo.setDescription(null);
        todo.setStatus(TodoStatus.OPEN);
        todo.setDueDate(dueDate);
        todo.setCompletedTime(null);
        return todo;
    }
}

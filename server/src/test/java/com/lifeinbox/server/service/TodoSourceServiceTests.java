package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.TodoSourceResponse;
import com.lifeinbox.server.entity.ActionCandidate;
import com.lifeinbox.server.entity.ActionCandidateStatus;
import com.lifeinbox.server.entity.ActionCandidateType;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.Todo;
import com.lifeinbox.server.entity.TodoStatus;
import com.lifeinbox.server.mapper.ActionCandidateMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TodoSourceServiceTests {

    private final TodoService todoService = mock(TodoService.class);
    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final ActionCandidateMapper actionCandidateMapper = mock(ActionCandidateMapper.class);
    private final TodoSourceService service = new TodoSourceService(
            todoService,
            inboxItemMapper,
            actionCandidateMapper
    );

    @Test
    void returnsFullReadOnlySourceContextWithInboxAndCandidateEvidence() {
        Todo todo = todo(1L, 100L, 200L, TodoStatus.OPEN);
        InboxItem inboxItem = inboxItem(100L, "TEXT", "课程设计", "  软件工程\n课程设计报告需要提交  ");
        inboxItem.setStatus("ACTIVE");
        inboxItem.setSourceUrl("https://example.com/course");
        inboxItem.setFileUrl("/api/files/12345678-1234-1234-1234-123456789abc.pdf");
        inboxItem.setCreatedTime(LocalDateTime.of(2026, 8, 20, 9, 30));
        ActionCandidate candidate = candidate(200L);
        stub(todo, inboxItem, candidate);

        TodoSourceResponse result = service.getSource(1L);

        assertEquals(1L, result.todoId());
        assertTrue(result.sourceAvailable());
        assertEquals(100L, result.inboxItem().id());
        assertEquals("TEXT", result.inboxItem().type());
        assertEquals("课程设计", result.inboxItem().title());
        assertEquals("软件工程 课程设计报告需要提交", result.inboxItem().preview());
        assertEquals("https://example.com/course", result.inboxItem().sourceUrl());
        assertEquals("/api/files/12345678-1234-1234-1234-123456789abc.pdf",
                result.inboxItem().fileUrl());
        assertEquals("ACTIVE", result.inboxItem().status());
        assertEquals(200L, result.actionCandidate().id());
        assertEquals(ActionCandidateType.DEADLINE, result.actionCandidate().actionType());
        assertEquals("2026年8月25日前", result.actionCandidate().deadlineText());
        assertEquals(LocalDate.of(2026, 8, 25), result.actionCandidate().deadline());
        assertEquals("课程设计报告需要在2026年8月25日前提交",
                result.actionCandidate().evidence());
        assertEquals(ActionCandidateStatus.ACCEPTED, result.actionCandidate().status());

        verify(todoService).get(1L);
        verify(inboxItemMapper).selectById(100L);
        verify(actionCandidateMapper).selectById(200L);
        verifyNoMoreInteractions(todoService, inboxItemMapper, actionCandidateMapper);
    }

    @ParameterizedTest
    @CsvSource({"URL", "FILE", "IMAGE"})
    void nonTextPreviewUsesOnlyPersistedSearchableContent(String type) {
        Todo todo = todo(2L, 101L, null, TodoStatus.OPEN);
        InboxItem inboxItem = inboxItem(101L, type, "标题回退", "不应使用原始 content");
        inboxItem.setSearchableContent(" 已持久化\n正文 ");
        when(todoService.get(2L)).thenReturn(todo);
        when(inboxItemMapper.selectById(101L)).thenReturn(inboxItem);

        TodoSourceResponse result = service.getSource(2L);

        assertEquals("已持久化 正文", result.inboxItem().preview());
        verify(inboxItemMapper).selectById(101L);
        verifyNoInteractions(actionCandidateMapper);
    }

    @Test
    void previewFallsBackToTitleAndIsBoundedByUnicodeCodePoints() {
        Todo todo = todo(3L, 102L, null, TodoStatus.OPEN);
        InboxItem inboxItem = inboxItem(102L, "IMAGE", "标题回退", null);
        inboxItem.setSearchableContent("😀".repeat(TodoSourceService.MAX_PREVIEW_CHARS + 10));
        when(todoService.get(3L)).thenReturn(todo);
        when(inboxItemMapper.selectById(102L)).thenReturn(inboxItem);

        String preview = service.getSource(3L).inboxItem().preview();

        assertEquals(TodoSourceService.MAX_PREVIEW_CHARS,
                preview.codePointCount(0, preview.length()));
        assertTrue(preview.endsWith("…"));

        inboxItem.setSearchableContent("   ");
        assertEquals("标题回退", service.getSource(3L).inboxItem().preview());
    }

    @Test
    void rejectsUnsafeLinksInsteadOfLeakingInternalPathsOrSchemes() {
        Todo todo = todo(4L, 103L, null, TodoStatus.OPEN);
        InboxItem inboxItem = inboxItem(103L, "FILE", "文件", null);
        inboxItem.setSourceUrl("file:///C:/secret.txt");
        inboxItem.setFileUrl("C:\\uploads\\secret.pdf");
        when(todoService.get(4L)).thenReturn(todo);
        when(inboxItemMapper.selectById(103L)).thenReturn(inboxItem);

        TodoSourceResponse result = service.getSource(4L);

        assertNull(result.inboxItem().sourceUrl());
        assertNull(result.inboxItem().fileUrl());

        inboxItem.setFileUrl("/api/files/../secret.pdf");
        assertNull(service.getSource(4L).inboxItem().fileUrl());
    }

    @Test
    void todoWithoutSourceReturnsSuccessfulUnavailableContextWithoutSourceQueries() {
        Todo todo = todo(5L, null, null, TodoStatus.OPEN);
        when(todoService.get(5L)).thenReturn(todo);

        TodoSourceResponse result = service.getSource(5L);

        assertEquals(5L, result.todoId());
        assertFalse(result.sourceAvailable());
        assertNull(result.inboxItem());
        assertNull(result.actionCandidate());
        verifyNoInteractions(inboxItemMapper, actionCandidateMapper);
    }

    @Test
    void missingTodoPreservesCurrentNotFoundAndDoesNotQuerySources() {
        when(todoService.get(99L)).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Todo 不存在"
        ));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.getSource(99L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(inboxItemMapper, actionCandidateMapper);
    }

    @Test
    void staleSourceReferencesDegradeToMissingOrPartialContext() {
        Todo missingBoth = todo(6L, 104L, 204L, TodoStatus.OPEN);
        when(todoService.get(6L)).thenReturn(missingBoth);
        when(inboxItemMapper.selectById(104L)).thenReturn(null);
        when(actionCandidateMapper.selectById(204L)).thenReturn(null);

        TodoSourceResponse unavailable = service.getSource(6L);
        assertFalse(unavailable.sourceAvailable());
        assertNull(unavailable.inboxItem());
        assertNull(unavailable.actionCandidate());

        Todo partial = todo(7L, 105L, 205L, TodoStatus.OPEN);
        InboxItem existingInbox = inboxItem(105L, "TEXT", "仍存在", "来源正文");
        when(todoService.get(7L)).thenReturn(partial);
        when(inboxItemMapper.selectById(105L)).thenReturn(existingInbox);
        when(actionCandidateMapper.selectById(205L)).thenReturn(null);

        TodoSourceResponse partialResult = service.getSource(7L);
        assertTrue(partialResult.sourceAvailable());
        assertEquals(105L, partialResult.inboxItem().id());
        assertNull(partialResult.actionCandidate());
    }

    @Test
    void archivedSourceAndCompletedTodoRemainTraceable() {
        Todo completed = todo(8L, 106L, null, TodoStatus.COMPLETED);
        InboxItem archived = inboxItem(106L, "TEXT", "已归档来源", "归档后仍可查看");
        archived.setStatus("ARCHIVED");
        when(todoService.get(8L)).thenReturn(completed);
        when(inboxItemMapper.selectById(106L)).thenReturn(archived);

        TodoSourceResponse result = service.getSource(8L);

        assertTrue(result.sourceAvailable());
        assertEquals("ARCHIVED", result.inboxItem().status());
        assertEquals("归档后仍可查看", result.inboxItem().preview());
    }

    @Test
    void sourceResolverHasOnlyReadSideDependenciesAndNoTransactionAnnotation() throws Exception {
        assertEquals(
                List.of(TodoService.class, InboxItemMapper.class, ActionCandidateMapper.class),
                Arrays.asList(TodoSourceService.class.getConstructors()[0].getParameterTypes())
        );
        assertFalse(TodoSourceService.class.getMethod("getSource", Long.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class));

        Todo todo = todo(9L, null, 209L, TodoStatus.COMPLETED);
        ActionCandidate candidate = candidate(209L);
        candidate.setDeadlineDate(null);
        when(todoService.get(9L)).thenReturn(todo);
        when(actionCandidateMapper.selectById(209L)).thenReturn(candidate);

        TodoSourceResponse result = service.getSource(9L);

        assertNull(result.actionCandidate().deadline());
        verify(todoService).get(9L);
        verify(actionCandidateMapper).selectById(209L);
        verify(inboxItemMapper, never()).selectById(org.mockito.ArgumentMatchers.anyLong());
        verifyNoMoreInteractions(todoService, inboxItemMapper, actionCandidateMapper);
    }

    private void stub(Todo todo, InboxItem inboxItem, ActionCandidate candidate) {
        when(todoService.get(todo.getId())).thenReturn(todo);
        when(inboxItemMapper.selectById(todo.getSourceInboxItemId())).thenReturn(inboxItem);
        when(actionCandidateMapper.selectById(todo.getSourceActionCandidateId())).thenReturn(candidate);
    }

    private Todo todo(
            Long id,
            Long inboxItemId,
            Long candidateId,
            TodoStatus status
    ) {
        Todo todo = new Todo();
        todo.setId(id);
        todo.setSourceInboxItemId(inboxItemId);
        todo.setSourceActionCandidateId(candidateId);
        todo.setStatus(status);
        return todo;
    }

    private InboxItem inboxItem(Long id, String type, String title, String content) {
        InboxItem inboxItem = new InboxItem();
        inboxItem.setId(id);
        inboxItem.setType(type);
        inboxItem.setTitle(title);
        inboxItem.setContent(content);
        return inboxItem;
    }

    private ActionCandidate candidate(Long id) {
        ActionCandidate candidate = new ActionCandidate();
        candidate.setId(id);
        candidate.setInboxItemId(100L);
        candidate.setActionType(ActionCandidateType.DEADLINE);
        candidate.setTitle("提交课程设计报告");
        candidate.setDeadlineText("2026年8月25日前");
        candidate.setDeadlineDate(LocalDate.of(2026, 8, 25));
        candidate.setEvidence("课程设计报告需要在2026年8月25日前提交");
        candidate.setStatus(ActionCandidateStatus.ACCEPTED);
        return candidate;
    }
}

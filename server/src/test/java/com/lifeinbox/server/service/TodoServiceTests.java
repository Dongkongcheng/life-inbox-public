package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.Todo;
import com.lifeinbox.server.entity.TodoStatus;
import com.lifeinbox.server.mapper.TodoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TodoServiceTests {

    private final TodoMapper todoMapper = mock(TodoMapper.class);
    private final TodoService service = new TodoService(todoMapper);

    @Test
    void basicTodoWithoutDescriptionDueDateOrSourceStartsOpen() {
        stubSuccessfulInsert(1L);

        Todo saved = service.create("  整理 Java 面试题  ", null, null, null, null);

        ArgumentCaptor<Todo> inserted = ArgumentCaptor.forClass(Todo.class);
        verify(todoMapper).insert(inserted.capture());
        assertSame(inserted.getValue(), saved);
        assertEquals("整理 Java 面试题", saved.getTitle());
        assertNull(saved.getDescription());
        assertNull(saved.getDueDate());
        assertNull(saved.getSourceInboxItemId());
        assertNull(saved.getSourceActionCandidateId());
        assertEquals(TodoStatus.OPEN, saved.getStatus());
        assertNull(saved.getCompletedTime());
    }

    @Test
    void todoPersistsDescriptionDueDateAndOptionalSourceLinks() {
        stubSuccessfulInsert(2L);
        LocalDate dueDate = LocalDate.of(2026, 8, 25);

        Todo saved = service.create(
                "提交课程设计报告",
                "  检查格式后上传  ",
                dueDate,
                100L,
                200L
        );

        assertEquals("检查格式后上传", saved.getDescription());
        assertEquals(dueDate, saved.getDueDate());
        assertEquals(100L, saved.getSourceInboxItemId());
        assertEquals(200L, saved.getSourceActionCandidateId());
        assertEquals(TodoStatus.OPEN, saved.getStatus());
        assertNull(saved.getCompletedTime());
    }

    @ParameterizedTest
    @MethodSource("invalidTitles")
    void nullBlankOrOversizedTitleIsRejectedBeforePersistence(String title) {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.create(title, null, null, null, null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(todoMapper, never()).insert(any(Todo.class));
    }

    @Test
    void duplicateSourceCandidateBecomesControlledConflict() {
        AtomicInteger inserts = new AtomicInteger();
        AtomicReference<Todo> firstTodo = new AtomicReference<>();
        when(todoMapper.insert(any(Todo.class))).thenAnswer(invocation -> {
            Todo todo = invocation.getArgument(0);
            if (inserts.getAndIncrement() == 0) {
                todo.setId(3L);
                firstTodo.set(todo);
                return 1;
            }
            throw new DuplicateKeyException("mock unique constraint");
        });
        when(todoMapper.selectById(3L)).thenAnswer(invocation -> firstTodo.get());

        service.create("第一次创建", null, null, 100L, 200L);
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.create("重复创建", null, null, 100L, 200L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void insertOrReloadFailureIsControlled() {
        when(todoMapper.insert(any(Todo.class))).thenReturn(0);
        ResponseStatusException insertFailure = assertThrows(
                ResponseStatusException.class,
                () -> service.create("保存失败", null, null, null, null)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, insertFailure.getStatusCode());

        TodoMapper reloadMapper = mock(TodoMapper.class);
        TodoService reloadService = new TodoService(reloadMapper);
        when(reloadMapper.insert(any(Todo.class))).thenAnswer(invocation -> {
            Todo todo = invocation.getArgument(0);
            todo.setId(4L);
            return 1;
        });
        when(reloadMapper.selectById(4L)).thenReturn(null);

        ResponseStatusException reloadFailure = assertThrows(
                ResponseStatusException.class,
                () -> reloadService.create("回读失败", null, null, null, null)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, reloadFailure.getStatusCode());
    }

    @Test
    void getReturnsExistingTodoAndRejectsMissingOrNullId() {
        Todo existing = new Todo();
        existing.setId(5L);
        when(todoMapper.selectById(5L)).thenReturn(existing);
        when(todoMapper.selectById(6L)).thenReturn(null);

        assertSame(existing, service.get(5L));
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.get(6L))
                        .getStatusCode()
        );
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.get(null))
                        .getStatusCode()
        );
        verify(todoMapper, never()).selectById(null);
    }

    @Test
    void findBySourceCandidateDelegatesToUniqueSourceQuery() {
        Todo existing = new Todo();
        existing.setId(6L);
        when(todoMapper.selectBySourceActionCandidateId(200L)).thenReturn(existing);

        assertSame(existing, service.findBySourceActionCandidateId(200L));
        verify(todoMapper).selectBySourceActionCandidateId(200L);
    }

    @Test
    void listDefaultsToOpenAndKeepsMapperDeterministicOrder() {
        Todo dueSoon = todo(10L, TodoStatus.OPEN, LocalDate.of(2026, 8, 25), null);
        Todo dueLater = todo(11L, TodoStatus.OPEN, LocalDate.of(2026, 9, 10), null);
        Todo noDueDate = todo(12L, TodoStatus.OPEN, null, null);
        when(todoMapper.selectOpenTodos()).thenReturn(List.of(dueSoon, dueLater, noDueDate));

        assertEquals(List.of(dueSoon, dueLater, noDueDate), service.list(null));
        assertEquals(List.of(dueSoon, dueLater, noDueDate), service.list("OPEN"));
        verify(todoMapper, times(2)).selectOpenTodos();
        verify(todoMapper, never()).selectCompletedTodos();
    }

    @Test
    void listCompletedUsesCompletedQueryAndDoesNotRequireSourceLinks() {
        Todo latest = todo(
                20L,
                TodoStatus.COMPLETED,
                null,
                LocalDateTime.of(2026, 8, 25, 12, 0)
        );
        latest.setSourceInboxItemId(null);
        latest.setSourceActionCandidateId(null);
        Todo older = todo(
                21L,
                TodoStatus.COMPLETED,
                null,
                LocalDateTime.of(2026, 8, 24, 12, 0)
        );
        when(todoMapper.selectCompletedTodos()).thenReturn(List.of(latest, older));

        assertEquals(List.of(latest, older), service.list("COMPLETED"));
        verify(todoMapper).selectCompletedTodos();
        verify(todoMapper, never()).selectOpenTodos();
    }

    @ParameterizedTest
    @MethodSource("invalidStatuses")
    void invalidListStatusIsRejectedBeforeQuery(String status) {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.list(status)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(todoMapper, never()).selectOpenTodos();
        verify(todoMapper, never()).selectCompletedTodos();
    }

    @Test
    void completeOpenTodoSetsJavaBusinessTimeAndReturnsAuthoritativeRow() {
        Todo open = todo(30L, TodoStatus.OPEN, null, null);
        when(todoMapper.selectByIdForUpdate(30L)).thenReturn(open);
        when(todoMapper.markOpenCompleted(eq(30L), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    open.setStatus(TodoStatus.COMPLETED);
                    open.setCompletedTime(invocation.getArgument(1));
                    return 1;
                });
        when(todoMapper.selectById(30L)).thenReturn(open);

        Todo completed = service.complete(30L);

        assertEquals(TodoStatus.COMPLETED, completed.getStatus());
        assertTrue(completed.getCompletedTime() != null);
        verify(todoMapper).selectByIdForUpdate(30L);
        verify(todoMapper).markOpenCompleted(eq(30L), any(LocalDateTime.class));
    }

    @Test
    void repeatedCompleteKeepsFirstCompletionTimeWithoutWritingAgain() {
        LocalDateTime firstCompletion = LocalDateTime.of(2026, 8, 25, 12, 30);
        Todo completed = todo(31L, TodoStatus.COMPLETED, null, firstCompletion);
        when(todoMapper.selectByIdForUpdate(31L)).thenReturn(completed);

        Todo result = service.complete(31L);

        assertSame(completed, result);
        assertEquals(firstCompletion, result.getCompletedTime());
        verify(todoMapper, never()).markOpenCompleted(anyLong(), any(LocalDateTime.class));
        verify(todoMapper, never()).selectById(31L);
    }

    @Test
    void reopenCompletedTodoClearsCompletionTime() {
        Todo completed = todo(
                40L,
                TodoStatus.COMPLETED,
                null,
                LocalDateTime.of(2026, 8, 25, 13, 0)
        );
        when(todoMapper.selectByIdForUpdate(40L)).thenReturn(completed);
        when(todoMapper.markReopened(40L)).thenAnswer(invocation -> {
            completed.setStatus(TodoStatus.OPEN);
            completed.setCompletedTime(null);
            return 1;
        });
        when(todoMapper.selectById(40L)).thenReturn(completed);

        Todo reopened = service.reopen(40L);

        assertEquals(TodoStatus.OPEN, reopened.getStatus());
        assertNull(reopened.getCompletedTime());
        verify(todoMapper).markReopened(40L);
    }

    @Test
    void repeatedReopenIsIdempotentAndRepairsOnlyAnomalousCompletionTime() {
        Todo open = todo(41L, TodoStatus.OPEN, null, null);
        when(todoMapper.selectByIdForUpdate(41L)).thenReturn(open);

        assertSame(open, service.reopen(41L));
        verify(todoMapper, never()).markReopened(41L);

        Todo anomalous = todo(
                42L,
                TodoStatus.OPEN,
                null,
                LocalDateTime.of(2026, 8, 25, 14, 0)
        );
        when(todoMapper.selectByIdForUpdate(42L)).thenReturn(anomalous);
        when(todoMapper.markReopened(42L)).thenAnswer(invocation -> {
            anomalous.setCompletedTime(null);
            return 1;
        });
        when(todoMapper.selectById(42L)).thenReturn(anomalous);

        Todo repaired = service.reopen(42L);
        assertEquals(TodoStatus.OPEN, repaired.getStatus());
        assertNull(repaired.getCompletedTime());
    }

    @Test
    void lifecycleReturnsNotFoundAndNeverDependsOnCandidateOrAiServices() {
        when(todoMapper.selectByIdForUpdate(99L)).thenReturn(null);

        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.complete(99L))
                        .getStatusCode()
        );
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.reopen(99L))
                        .getStatusCode()
        );
        assertEquals(
                List.of(TodoMapper.class),
                Arrays.asList(TodoService.class.getConstructors()[0].getParameterTypes())
        );
    }

    @Test
    void writeOperationsDefineBusinessTransactionBoundaries() throws NoSuchMethodException {
        Method create = TodoService.class.getMethod(
                "create",
                String.class,
                String.class,
                LocalDate.class,
                Long.class,
                Long.class
        );
        Method complete = TodoService.class.getMethod("complete", Long.class);
        Method reopen = TodoService.class.getMethod("reopen", Long.class);

        assertTrue(create.isAnnotationPresent(Transactional.class));
        assertTrue(complete.isAnnotationPresent(Transactional.class));
        assertTrue(reopen.isAnnotationPresent(Transactional.class));
    }

    private void stubSuccessfulInsert(Long id) {
        AtomicReference<Todo> inserted = new AtomicReference<>();
        when(todoMapper.insert(any(Todo.class))).thenAnswer(invocation -> {
            Todo todo = invocation.getArgument(0);
            todo.setId(id);
            inserted.set(todo);
            return 1;
        });
        when(todoMapper.selectById(anyLong())).thenAnswer(invocation -> inserted.get());
    }

    private static Stream<String> invalidTitles() {
        return Stream.of(null, "", "   ", "长".repeat(TodoService.MAX_TITLE_CHARS + 1));
    }

    private static Stream<String> invalidStatuses() {
        return Stream.of("", "open", "MAGIC", "OPEN ");
    }

    private Todo todo(
            Long id,
            TodoStatus status,
            LocalDate dueDate,
            LocalDateTime completedTime
    ) {
        Todo todo = new Todo();
        todo.setId(id);
        todo.setTitle("Todo " + id);
        todo.setStatus(status);
        todo.setDueDate(dueDate);
        todo.setCompletedTime(completedTime);
        return todo;
    }
}

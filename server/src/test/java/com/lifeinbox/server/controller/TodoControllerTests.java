package com.lifeinbox.server.controller;

import com.lifeinbox.server.entity.Todo;
import com.lifeinbox.server.entity.TodoStatus;
import com.lifeinbox.server.service.TodoService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TodoControllerTests {

    @Test
    void listDefaultsToOpenAndReturnsFullProductDto() throws Exception {
        TodoService service = mock(TodoService.class);
        Todo todo = todo(1L, TodoStatus.OPEN, null);
        todo.setSourceInboxItemId(null);
        todo.setSourceActionCandidateId(null);
        when(service.list(null)).thenReturn(List.of(todo));
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(get("/api/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].sourceInboxItemId").doesNotExist())
                .andExpect(jsonPath("$[0].sourceActionCandidateId").doesNotExist())
                .andExpect(jsonPath("$[0].title").value("Todo 1"))
                .andExpect(jsonPath("$[0].description").doesNotExist())
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].dueDate").value("2026-08-25"))
                .andExpect(jsonPath("$[0].completedTime").doesNotExist())
                .andExpect(jsonPath("$[0].createdTime").value("2026-08-24T10:00:00"));

        verify(service).list(null);
    }

    @Test
    void listPassesOnlyExplicitOpenOrCompletedStatusToService() throws Exception {
        TodoService service = mock(TodoService.class);
        when(service.list("OPEN")).thenReturn(List.of());
        when(service.list("COMPLETED")).thenReturn(List.of());
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(get("/api/todos").param("status", "OPEN"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/todos").param("status", "COMPLETED"))
                .andExpect(status().isOk());

        verify(service).list("OPEN");
        verify(service).list("COMPLETED");
    }

    @Test
    void invalidStatusReturnsControlledBadRequest() throws Exception {
        TodoService service = mock(TodoService.class);
        when(service.list("MAGIC")).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Todo 状态只允许 OPEN 或 COMPLETED"
        ));

        mockMvc(service).perform(get("/api/todos").param("status", "MAGIC"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completeApiIsRetrySafeAndReturnsFirstCompletionTime() throws Exception {
        TodoService service = mock(TodoService.class);
        LocalDateTime completedTime = LocalDateTime.of(2026, 8, 25, 12, 30);
        Todo completed = todo(2L, TodoStatus.COMPLETED, completedTime);
        when(service.complete(2L)).thenReturn(completed);
        MockMvc mockMvc = mockMvc(service);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/todos/2/complete"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.completedTime").value("2026-08-25T12:30:00"));
        }
        verify(service, times(2)).complete(2L);
    }

    @Test
    void reopenApiIsRetrySafeAndClearsCompletionTime() throws Exception {
        TodoService service = mock(TodoService.class);
        Todo open = todo(3L, TodoStatus.OPEN, null);
        when(service.reopen(3L)).thenReturn(open);
        MockMvc mockMvc = mockMvc(service);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/todos/3/reopen"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("OPEN"))
                    .andExpect(jsonPath("$.completedTime").doesNotExist());
        }
        verify(service, times(2)).reopen(3L);
    }

    @Test
    void missingTodoReturnsNotFoundForBothLifecycleApis() throws Exception {
        TodoService service = mock(TodoService.class);
        when(service.complete(99L)).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Todo 不存在"
        ));
        when(service.reopen(99L)).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Todo 不存在"
        ));
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(post("/api/todos/99/complete"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/todos/99/reopen"))
                .andExpect(status().isNotFound());
    }

    private MockMvc mockMvc(TodoService service) {
        return MockMvcBuilders.standaloneSetup(new TodoController(service)).build();
    }

    private Todo todo(Long id, TodoStatus status, LocalDateTime completedTime) {
        Todo todo = new Todo();
        todo.setId(id);
        todo.setSourceInboxItemId(100L);
        todo.setSourceActionCandidateId(200L);
        todo.setTitle("Todo " + id);
        todo.setDescription(null);
        todo.setStatus(status);
        todo.setDueDate(LocalDate.of(2026, 8, 25));
        todo.setCompletedTime(completedTime);
        todo.setCreatedTime(LocalDateTime.of(2026, 8, 24, 10, 0));
        todo.setUpdatedTime(LocalDateTime.of(2026, 8, 25, 10, 0));
        return todo;
    }
}

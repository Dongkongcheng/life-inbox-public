package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.ActionCandidateSourceResponse;
import com.lifeinbox.server.dto.InboxSourceSummaryResponse;
import com.lifeinbox.server.dto.TodoSourceResponse;
import com.lifeinbox.server.entity.ActionCandidateStatus;
import com.lifeinbox.server.entity.ActionCandidateType;
import com.lifeinbox.server.service.TodoSourceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TodoSourceControllerTests {

    @Test
    void returnsFullSourceContextFromDedicatedLazyEndpoint() throws Exception {
        TodoSourceService service = mock(TodoSourceService.class);
        when(service.getSource(1L)).thenReturn(new TodoSourceResponse(
                1L,
                true,
                new InboxSourceSummaryResponse(
                        100L,
                        "TEXT",
                        "课程设计",
                        "报告需要提交",
                        null,
                        null,
                        "ARCHIVED",
                        LocalDateTime.of(2026, 8, 20, 9, 30)
                ),
                new ActionCandidateSourceResponse(
                        200L,
                        ActionCandidateType.DEADLINE,
                        "提交课程设计报告",
                        "下周五前",
                        LocalDate.of(2026, 8, 28),
                        "下周五前提交报告",
                        ActionCandidateStatus.ACCEPTED
                )
        ));

        mockMvc(service).perform(get("/api/todos/1/source"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todoId").value(1))
                .andExpect(jsonPath("$.sourceAvailable").value(true))
                .andExpect(jsonPath("$.inboxItem.id").value(100))
                .andExpect(jsonPath("$.inboxItem.preview").value("报告需要提交"))
                .andExpect(jsonPath("$.inboxItem.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.actionCandidate.id").value(200))
                .andExpect(jsonPath("$.actionCandidate.deadlineText").value("下周五前"))
                .andExpect(jsonPath("$.actionCandidate.deadline").value("2026-08-28"))
                .andExpect(jsonPath("$.actionCandidate.evidence").value("下周五前提交报告"));
        verify(service).getSource(1L);
    }

    @Test
    void noSourceIsAValidSuccessResponse() throws Exception {
        TodoSourceService service = mock(TodoSourceService.class);
        when(service.getSource(2L)).thenReturn(new TodoSourceResponse(2L, false, null, null));

        mockMvc(service).perform(get("/api/todos/2/source"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todoId").value(2))
                .andExpect(jsonPath("$.sourceAvailable").value(false))
                .andExpect(jsonPath("$.inboxItem").doesNotExist())
                .andExpect(jsonPath("$.actionCandidate").doesNotExist());
    }

    @Test
    void missingTodoKeepsNotFoundSemantics() throws Exception {
        TodoSourceService service = mock(TodoSourceService.class);
        when(service.getSource(99L)).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Todo 不存在"
        ));

        mockMvc(service).perform(get("/api/todos/99/source"))
                .andExpect(status().isNotFound());
    }

    private MockMvc mockMvc(TodoSourceService service) {
        return MockMvcBuilders.standaloneSetup(new TodoSourceController(service)).build();
    }
}

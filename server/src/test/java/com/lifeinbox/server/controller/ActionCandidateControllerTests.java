package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.ActionCandidateAcceptanceResponse;
import com.lifeinbox.server.dto.ActionCandidateResponse;
import com.lifeinbox.server.dto.TodoResponse;
import com.lifeinbox.server.entity.ActionCandidateStatus;
import com.lifeinbox.server.entity.ActionCandidateType;
import com.lifeinbox.server.entity.TodoStatus;
import com.lifeinbox.server.service.ActionCandidateDecisionService;
import com.lifeinbox.server.service.ActionCandidateService;
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

class ActionCandidateControllerTests {

    @Test
    void manualExtractionReturnsPersistedProductCandidates() throws Exception {
        ActionCandidateService service = mock(ActionCandidateService.class);
        ActionCandidateDecisionService decisionService = mock(ActionCandidateDecisionService.class);
        ActionCandidateController controller = new ActionCandidateController(service, decisionService);
        when(service.extract(100L)).thenReturn(List.of(candidate()));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/inbox/100/action-candidates/extract"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].inboxItemId").value(100))
                .andExpect(jsonPath("$[0].actionType").value("DEADLINE"))
                .andExpect(jsonPath("$[0].deadlineText").value("2026年8月25日前"))
                .andExpect(jsonPath("$[0].deadline").value("2026-08-25"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        verify(service).extract(100L);
    }

    @Test
    void candidateQueryUsesProductRoute() throws Exception {
        ActionCandidateService service = mock(ActionCandidateService.class);
        ActionCandidateDecisionService decisionService = mock(ActionCandidateDecisionService.class);
        ActionCandidateController controller = new ActionCandidateController(service, decisionService);
        when(service.list(100L)).thenReturn(List.of(candidate()));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/inbox/100/action-candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("提交软件工程课程设计报告"))
                .andExpect(jsonPath("$[0].evidence").value("2026年8月25日前提交软件工程课程设计报告"));
        verify(service).list(100L);
    }

    @Test
    void acceptApiReturnsAcceptedCandidateAndSameTodoOnRetry() throws Exception {
        ActionCandidateService service = mock(ActionCandidateService.class);
        ActionCandidateDecisionService decisionService = mock(ActionCandidateDecisionService.class);
        ActionCandidateController controller = new ActionCandidateController(service, decisionService);
        ActionCandidateAcceptanceResponse accepted = new ActionCandidateAcceptanceResponse(
                candidate(ActionCandidateStatus.ACCEPTED),
                new TodoResponse(
                        200L,
                        "提交软件工程课程设计报告",
                        TodoStatus.OPEN,
                        LocalDate.of(2026, 8, 25)
                )
        );
        when(decisionService.accept(100L, 1L)).thenReturn(accepted);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/inbox/100/action-candidates/1/accept"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.candidate.id").value(1))
                    .andExpect(jsonPath("$.candidate.status").value("ACCEPTED"))
                    .andExpect(jsonPath("$.todo.id").value(200))
                    .andExpect(jsonPath("$.todo.title")
                            .value("提交软件工程课程设计报告"))
                    .andExpect(jsonPath("$.todo.status").value("OPEN"))
                    .andExpect(jsonPath("$.todo.dueDate").value("2026-08-25"));
        }
        verify(decisionService, times(2)).accept(100L, 1L);
    }

    @Test
    void dismissApiReturnsDismissedCandidateAndIsRetrySafe() throws Exception {
        ActionCandidateService service = mock(ActionCandidateService.class);
        ActionCandidateDecisionService decisionService = mock(ActionCandidateDecisionService.class);
        ActionCandidateController controller = new ActionCandidateController(service, decisionService);
        when(decisionService.dismiss(100L, 1L))
                .thenReturn(candidate(ActionCandidateStatus.DISMISSED));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/inbox/100/action-candidates/1/dismiss"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.status").value("DISMISSED"));
        }
        verify(decisionService, times(2)).dismiss(100L, 1L);
    }

    @Test
    void invalidOppositeTransitionsReturnConflict() throws Exception {
        ActionCandidateService service = mock(ActionCandidateService.class);
        ActionCandidateDecisionService decisionService = mock(ActionCandidateDecisionService.class);
        ActionCandidateController controller = new ActionCandidateController(service, decisionService);
        when(decisionService.accept(100L, 1L)).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "已忽略的 Action Candidate 不能接受"
        ));
        when(decisionService.dismiss(100L, 1L)).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "已接受的 Action Candidate 不能忽略"
        ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/inbox/100/action-candidates/1/accept"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/inbox/100/action-candidates/1/dismiss"))
                .andExpect(status().isConflict());
    }

    @Test
    void missingCandidateReturnsNotFoundForBothDecisionApis() throws Exception {
        ActionCandidateService service = mock(ActionCandidateService.class);
        ActionCandidateDecisionService decisionService = mock(ActionCandidateDecisionService.class);
        ActionCandidateController controller = new ActionCandidateController(service, decisionService);
        when(decisionService.accept(100L, 99L)).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Action Candidate 不存在"
        ));
        when(decisionService.dismiss(100L, 99L)).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Action Candidate 不存在"
        ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/inbox/100/action-candidates/99/accept"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/inbox/100/action-candidates/99/dismiss"))
                .andExpect(status().isNotFound());
    }

    private ActionCandidateResponse candidate() {
        return candidate(ActionCandidateStatus.PENDING);
    }

    private ActionCandidateResponse candidate(ActionCandidateStatus status) {
        return new ActionCandidateResponse(
                1L,
                100L,
                ActionCandidateType.DEADLINE,
                "提交软件工程课程设计报告",
                "2026年8月25日前",
                LocalDate.of(2026, 8, 25),
                "2026年8月25日前提交软件工程课程设计报告",
                status,
                LocalDateTime.of(2026, 8, 24, 10, 0),
                LocalDateTime.of(2026, 8, 24, 10, 0)
        );
    }
}

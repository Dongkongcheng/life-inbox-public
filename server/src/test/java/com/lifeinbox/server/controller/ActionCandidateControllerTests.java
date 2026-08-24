package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.ActionCandidateResponse;
import com.lifeinbox.server.entity.ActionCandidateStatus;
import com.lifeinbox.server.entity.ActionCandidateType;
import com.lifeinbox.server.service.ActionCandidateService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
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
        ActionCandidateController controller = new ActionCandidateController(service);
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
        ActionCandidateController controller = new ActionCandidateController(service);
        when(service.list(100L)).thenReturn(List.of(candidate()));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/inbox/100/action-candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("提交软件工程课程设计报告"))
                .andExpect(jsonPath("$[0].evidence").value("2026年8月25日前提交软件工程课程设计报告"));
        verify(service).list(100L);
    }

    private ActionCandidateResponse candidate() {
        return new ActionCandidateResponse(
                1L,
                100L,
                ActionCandidateType.DEADLINE,
                "提交软件工程课程设计报告",
                "2026年8月25日前",
                LocalDate.of(2026, 8, 25),
                "2026年8月25日前提交软件工程课程设计报告",
                ActionCandidateStatus.PENDING,
                LocalDateTime.of(2026, 8, 24, 10, 0),
                LocalDateTime.of(2026, 8, 24, 10, 0)
        );
    }
}

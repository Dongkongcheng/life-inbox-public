package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.RelationBackfillResponse;
import com.lifeinbox.server.service.RelationBackfillService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RelationBackfillControllerTests {

    @Test
    void endpointUsesDefaultLimitAndReturnsOnlySchedulingSummary() throws Exception {
        RelationBackfillService service = mock(RelationBackfillService.class);
        when(service.schedule(10)).thenReturn(new RelationBackfillResponse(10, 18, 8, 7, 3));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new RelationBackfillController(service)
        ).build();

        mockMvc.perform(post("/api/relations/backfill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedLimit").value(10))
                .andExpect(jsonPath("$.scannedCount").value(18))
                .andExpect(jsonPath("$.scheduledCount").value(8))
                .andExpect(jsonPath("$.skippedNotReadyCount").value(7))
                .andExpect(jsonPath("$.claimConflictCount").value(3))
                .andExpect(jsonPath("$.attemptId").doesNotExist())
                .andExpect(jsonPath("$.vector").doesNotExist());

        verify(service).schedule(10);
    }

    @Test
    void endpointPassesCustomLimitAndRejectsNonNumericInput() throws Exception {
        RelationBackfillService service = mock(RelationBackfillService.class);
        when(service.schedule(5)).thenReturn(new RelationBackfillResponse(5, 5, 5, 0, 0));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new RelationBackfillController(service)
        ).build();

        mockMvc.perform(post("/api/relations/backfill").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedLimit").value(5));
        mockMvc.perform(post("/api/relations/backfill").param("limit", "invalid"))
                .andExpect(status().isBadRequest());

        verify(service).schedule(5);
    }
}

package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.RelationProcessingResponse;
import com.lifeinbox.server.entity.RelationProcessingStatus;
import com.lifeinbox.server.service.RelationProcessingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RelationProcessingControllerTests {

    @Test
    void manualEndpointReturnsMinimalStatusAndCountsWithoutInternalMetadata() throws Exception {
        RelationProcessingService service = mock(RelationProcessingService.class);
        when(service.processManual(100L)).thenReturn(new RelationProcessingResponse(
                RelationProcessingStatus.SUCCESS,
                3,
                2,
                1,
                0
        ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new RelationProcessingController(service)
        ).build();

        mockMvc.perform(post("/api/inbox/100/relations/discover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.discoveredCount").value(3))
                .andExpect(jsonPath("$.persistedNewCount").value(2))
                .andExpect(jsonPath("$.alreadyExistingCount").value(1))
                .andExpect(jsonPath("$.skippedInvalidCount").value(0))
                .andExpect(jsonPath("$.attemptId").doesNotExist())
                .andExpect(jsonPath("$.relations").doesNotExist());

        verify(service).processManual(100L);
    }

    @Test
    void processingConflictIsPreservedAsHttp409() throws Exception {
        RelationProcessingService service = mock(RelationProcessingService.class);
        when(service.processManual(101L)).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Relation Discovery 正在进行中"
        ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new RelationProcessingController(service)
        ).build();

        mockMvc.perform(post("/api/inbox/101/relations/discover"))
                .andExpect(status().isConflict());
    }
}

package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.RelatedInboxItemResponse;
import com.lifeinbox.server.dto.RelatedInboxItemSummaryResponse;
import com.lifeinbox.server.entity.RelationType;
import com.lifeinbox.server.service.RelatedInboxItemService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RelatedInboxItemControllerTests {

    @Test
    void defaultRequestReturnsBoundedProductJsonWithoutPersistenceDetails() throws Exception {
        RelatedInboxItemService service = mock(RelatedInboxItemService.class);
        when(service.listRelated(100L, null)).thenReturn(List.of(response(200L)));
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(get("/api/inbox/100/related"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].relationType").value("RELATED_TO"))
                .andExpect(jsonPath("$[0].relatedInboxItem.id").value(200))
                .andExpect(jsonPath("$[0].relatedInboxItem.type").value("TEXT"))
                .andExpect(jsonPath("$[0].relatedInboxItem.title").value("Redisson 分布式锁"))
                .andExpect(jsonPath("$[0].relatedInboxItem.summary").value("分布式锁摘要"))
                .andExpect(jsonPath("$[0].relatedInboxItem.category").value("技术学习"))
                .andExpect(jsonPath("$[0].relatedInboxItem.preview").value("Redisson 提供分布式锁"))
                .andExpect(jsonPath("$[0].relatedInboxItem.favorite").value(true))
                .andExpect(jsonPath("$[0].relatedInboxItem.createdTime")
                        .value("2026-08-28T10:00:00"))
                .andExpect(jsonPath("$[0].leftInboxItemId").doesNotExist())
                .andExpect(jsonPath("$[0].rightInboxItemId").doesNotExist())
                .andExpect(jsonPath("$[0].relationId").doesNotExist())
                .andExpect(jsonPath("$[0].semanticScore").doesNotExist())
                .andExpect(jsonPath("$[0].relationScore").doesNotExist())
                .andExpect(jsonPath("$[0].evidence").doesNotExist())
                .andExpect(jsonPath("$[0].relatedInboxItem.content").doesNotExist())
                .andExpect(jsonPath("$[0].relatedInboxItem.searchableContent").doesNotExist())
                .andExpect(jsonPath("$[0].relatedInboxItem.aiAttemptId").doesNotExist())
                .andExpect(jsonPath("$[0].relatedInboxItem.actionAttemptId").doesNotExist());

        verify(service).listRelated(100L, null);
    }

    @Test
    void activeSourceWithoutRelationsReturnsSuccessfulEmptyArray() throws Exception {
        RelatedInboxItemService service = mock(RelatedInboxItemService.class);
        when(service.listRelated(100L, null)).thenReturn(List.of());
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(get("/api/inbox/100/related"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void customLimitIsForwardedToReadService() throws Exception {
        RelatedInboxItemService service = mock(RelatedInboxItemService.class);
        when(service.listRelated(100L, 5)).thenReturn(List.of());
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(get("/api/inbox/100/related").param("limit", "5"))
                .andExpect(status().isOk());

        verify(service).listRelated(100L, 5);
    }

    @Test
    void missingOrArchivedSourceReturnsNotFound() throws Exception {
        RelatedInboxItemService service = mock(RelatedInboxItemService.class);
        when(service.listRelated(404L, null)).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "InboxItem 不存在"
        ));
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(get("/api/inbox/404/related"))
                .andExpect(status().isNotFound());
    }

    @Test
    void outOfRangeAndMalformedLimitsReturnBadRequest() throws Exception {
        RelatedInboxItemService service = mock(RelatedInboxItemService.class);
        when(service.listRelated(100L, 0)).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Related Items limit 必须在 1 到 20 之间"
        ));
        when(service.listRelated(100L, 21)).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Related Items limit 必须在 1 到 20 之间"
        ));
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(get("/api/inbox/100/related").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/inbox/100/related").param("limit", "21"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/inbox/100/related").param("limit", "invalid"))
                .andExpect(status().isBadRequest());

        verify(service).listRelated(100L, 0);
        verify(service).listRelated(100L, 21);
    }

    @Test
    void controllerDependsOnlyOnReadService() {
        RelatedInboxItemService service = mock(RelatedInboxItemService.class);

        new RelatedInboxItemController(service);

        verifyNoInteractions(service);
    }

    private MockMvc mockMvc(RelatedInboxItemService service) {
        return MockMvcBuilders.standaloneSetup(new RelatedInboxItemController(service)).build();
    }

    private RelatedInboxItemResponse response(Long id) {
        return new RelatedInboxItemResponse(
                RelationType.RELATED_TO,
                new RelatedInboxItemSummaryResponse(
                        id,
                        "TEXT",
                        "Redisson 分布式锁",
                        "分布式锁摘要",
                        "技术学习",
                        "Redisson 提供分布式锁",
                        true,
                        LocalDateTime.of(2026, 8, 28, 10, 0)
                )
        );
    }
}

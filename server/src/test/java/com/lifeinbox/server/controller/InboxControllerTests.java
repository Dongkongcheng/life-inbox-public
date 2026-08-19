package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.AiEntityResponse;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.service.InboxAnalyzeService;
import com.lifeinbox.server.service.InboxService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboxControllerTests {

    @Test
    void analyzeAndLegacySummaryEndpointsShareOneAnalyzeFlow() {
        InboxService inboxService = mock(InboxService.class);
        InboxAnalyzeService analyzeService = mock(InboxAnalyzeService.class);
        InboxController controller = new InboxController(inboxService, analyzeService);
        InboxItem analyzedItem = new InboxItem();
        analyzedItem.setId(1L);
        analyzedItem.setSummary("摘要");
        analyzedItem.setCategory("技术学习");
        analyzedItem.setTags(List.of("Java"));
        analyzedItem.setKeywords(List.of("ChatModel"));
        analyzedItem.setEntities(List.of(new AiEntityResponse("Spring AI", "TECHNOLOGY")));
        when(analyzeService.analyze(1L)).thenReturn(analyzedItem);

        assertEquals(analyzedItem, controller.analyze(1L));
        assertEquals(analyzedItem, controller.generateSummary(1L));
        verify(analyzeService, times(2)).analyze(1L);
    }
}

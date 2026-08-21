package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.AiEntityResponse;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.exception.FileAnalyzeException;
import com.lifeinbox.server.exception.UrlAnalyzeException;
import com.lifeinbox.server.service.InboxAnalyzeService;
import com.lifeinbox.server.service.InboxService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Test
    void analyzeEndpointReturnsSafeStructuredUrlFailure() throws Exception {
        InboxService inboxService = mock(InboxService.class);
        InboxAnalyzeService analyzeService = mock(InboxAnalyzeService.class);
        InboxController controller = new InboxController(inboxService, analyzeService);
        UrlAnalyzeException timeout = UrlAnalyzeException.fromUpstream(
                "URL_FETCH_TIMEOUT",
                408
        ).orElseThrow();
        when(analyzeService.analyze(8L)).thenThrow(timeout);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(post("/api/inbox/8/ai/analyze"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("URL_FETCH_TIMEOUT"))
                .andExpect(jsonPath("$.detail").value("网页读取超时"));
    }

    @Test
    void analyzeEndpointReturnsSafeStructuredFileFailure() throws Exception {
        InboxService inboxService = mock(InboxService.class);
        InboxAnalyzeService analyzeService = mock(InboxAnalyzeService.class);
        InboxController controller = new InboxController(inboxService, analyzeService);
        FileAnalyzeException noText = FileAnalyzeException.fromUpstream(
                "FILE_PDF_NO_TEXT",
                422
        ).orElseThrow();
        when(analyzeService.analyze(9L)).thenThrow(noText);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(post("/api/inbox/9/ai/analyze"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FILE_PDF_NO_TEXT"))
                .andExpect(jsonPath("$.detail").value("无法从 PDF 提取有效文本，文件可能需要 OCR"));
    }
}

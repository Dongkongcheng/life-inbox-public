package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.AiEntityResponse;
import com.lifeinbox.server.entity.AiProcessingStatus;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.exception.FileAnalyzeException;
import com.lifeinbox.server.exception.ImageAnalyzeException;
import com.lifeinbox.server.exception.UrlAnalyzeException;
import com.lifeinbox.server.service.InboxAnalyzeService;
import com.lifeinbox.server.service.InboxService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InboxControllerTests {

    @Test
    void listResponseIncludesAiProcessingStateFields() throws Exception {
        InboxService inboxService = mock(InboxService.class);
        InboxAnalyzeService analyzeService = mock(InboxAnalyzeService.class);
        InboxController controller = new InboxController(inboxService, analyzeService);
        InboxItem item = new InboxItem();
        item.setId(1L);
        item.setAiStatus(AiProcessingStatus.FAILED);
        item.setAiErrorMessage("网页读取超时");
        item.setAiStartedTime(LocalDateTime.of(2026, 8, 20, 21, 0));
        item.setAiFinishedTime(LocalDateTime.of(2026, 8, 20, 21, 1));
        when(inboxService.list()).thenReturn(List.of(item));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].aiStatus").value("FAILED"))
                .andExpect(jsonPath("$[0].aiErrorMessage").value("网页读取超时"))
                .andExpect(jsonPath("$[0].aiStartedTime").value("2026-08-20T21:00:00"))
                .andExpect(jsonPath("$[0].aiFinishedTime").value("2026-08-20T21:01:00"));
    }

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

    @Test
    void analyzeEndpointReturnsSafeStructuredImageFailure() throws Exception {
        InboxService inboxService = mock(InboxService.class);
        InboxAnalyzeService analyzeService = mock(InboxAnalyzeService.class);
        InboxController controller = new InboxController(inboxService, analyzeService);
        ImageAnalyzeException noText = ImageAnalyzeException.fromUpstream(
                "IMAGE_TEXT_EMPTY",
                422
        ).orElseThrow();
        when(analyzeService.analyze(10L)).thenThrow(noText);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(post("/api/inbox/10/ai/analyze"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IMAGE_TEXT_EMPTY"))
                .andExpect(jsonPath("$.detail").value("当前图片未识别到足够的文字内容"));
    }

    @Test
    void duplicateAnalyzeReturnsConflict() throws Exception {
        InboxService inboxService = mock(InboxService.class);
        InboxAnalyzeService analyzeService = mock(InboxAnalyzeService.class);
        InboxController controller = new InboxController(inboxService, analyzeService);
        when(analyzeService.analyze(11L)).thenThrow(
                new ResponseStatusException(HttpStatus.CONFLICT, "AI 分析正在进行中")
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/inbox/11/ai/analyze"))
                .andExpect(status().isConflict());
    }
}

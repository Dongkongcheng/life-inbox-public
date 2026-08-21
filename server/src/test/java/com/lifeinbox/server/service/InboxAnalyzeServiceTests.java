package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.AiAnalyzeResponse;
import com.lifeinbox.server.dto.AiEntityResponse;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.exception.FileAnalyzeException;
import com.lifeinbox.server.exception.UrlAnalyzeException;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InboxAnalyzeServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final AiServiceClient aiServiceClient = mock(AiServiceClient.class);
    private final FileStorageService fileStorageService = mock(FileStorageService.class);
    private final InboxAnalysisPersistenceService persistenceService = mock(
            InboxAnalysisPersistenceService.class
    );
    private final InboxAnalyzeService analyzeService = new InboxAnalyzeService(
            inboxItemMapper,
            aiServiceClient,
            fileStorageService,
            persistenceService
    );

    @Test
    void rejectsMissingInboxItem() {
        when(inboxItemMapper.selectById(99L)).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> analyzeService.analyze(99L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(aiServiceClient, persistenceService);
    }

    @Test
    void analyzesUrlThenUsesTheSameValidationAndPersistence() {
        InboxItem original = item("URL", null);
        original.setTitle("Spring AI 文档");
        original.setSourceUrl(" https://example.com/spring-ai ");
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        AiAnalyzeResponse response = new AiAnalyzeResponse(
                "  URL 摘要  ",
                "技术学习",
                List.of(" Spring　AI "),
                List.of(" ChatModel "),
                List.of(new AiEntityResponse(" Spring　AI ", "TECHNOLOGY"))
        );
        when(aiServiceClient.analyzeUrl(
                "Spring AI 文档",
                "https://example.com/spring-ai"
        )).thenReturn(response);
        InboxItem updated = item("URL", null);
        updated.setSourceUrl("https://example.com/spring-ai");
        when(persistenceService.replaceAnalysis(
                1L,
                "URL 摘要",
                "技术学习",
                List.of(new NormalizedTag("Spring AI", "spring ai")),
                List.of("ChatModel"),
                List.of(new NormalizedEntity("Spring AI", "TECHNOLOGY"))
        )).thenReturn(updated);

        assertEquals(updated, analyzeService.analyze(1L));

        verify(aiServiceClient).analyzeUrl(
                "Spring AI 文档",
                "https://example.com/spring-ai"
        );
        verify(aiServiceClient, never()).analyze(any(), any());
        verify(persistenceService).replaceAnalysis(
                1L,
                "URL 摘要",
                "技术学习",
                List.of(new NormalizedTag("Spring AI", "spring ai")),
                List.of("ChatModel"),
                List.of(new NormalizedEntity("Spring AI", "TECHNOLOGY"))
        );
    }

    @Test
    void rejectsUrlWithBlankSourceBeforeCallingPython() {
        InboxItem item = item("URL", null);
        item.setSourceUrl("  ");
        when(inboxItemMapper.selectById(1L)).thenReturn(item);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> analyzeService.analyze(1L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(aiServiceClient, persistenceService);
    }

    @Test
    void analyzesFileThenUsesTheSameValidationAndPersistence() {
        InboxItem original = item("FILE", null);
        original.setTitle("架构说明.pdf");
        original.setFileUrl("/api/files/00000000-0000-0000-0000-000000000001.pdf");
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        Resource resource = new ByteArrayResource("pdf".getBytes()) {
            @Override
            public String getFilename() {
                return "00000000-0000-0000-0000-000000000001.pdf";
            }
        };
        FileStorageService.AnalyzableFile file = new FileStorageService.AnalyzableFile(
                resource,
                MediaType.APPLICATION_PDF,
                3
        );
        when(fileStorageService.loadForAnalysis(original.getFileUrl())).thenReturn(file);
        AiAnalyzeResponse response = new AiAnalyzeResponse(
                "FILE 摘要",
                "技术学习",
                List.of("PDF"),
                List.of("文档解析"),
                List.of()
        );
        when(aiServiceClient.analyzeFile(
                "架构说明.pdf",
                resource,
                MediaType.APPLICATION_PDF
        )).thenReturn(response);
        InboxItem updated = item("FILE", null);
        when(persistenceService.replaceAnalysis(
                1L,
                "FILE 摘要",
                "技术学习",
                List.of(new NormalizedTag("PDF", "pdf")),
                List.of("文档解析"),
                List.of()
        )).thenReturn(updated);

        assertEquals(updated, analyzeService.analyze(1L));

        verify(fileStorageService).loadForAnalysis(original.getFileUrl());
        verify(aiServiceClient).analyzeFile(
                "架构说明.pdf",
                resource,
                MediaType.APPLICATION_PDF
        );
        verify(persistenceService).replaceAnalysis(
                1L,
                "FILE 摘要",
                "技术学习",
                List.of(new NormalizedTag("PDF", "pdf")),
                List.of("文档解析"),
                List.of()
        );
    }

    @Test
    void rejectsImageItem() {
        when(inboxItemMapper.selectById(2L)).thenReturn(item("IMAGE", null));

        ResponseStatusException imageException = assertThrows(
                ResponseStatusException.class,
                () -> analyzeService.analyze(2L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, imageException.getStatusCode());
        verifyNoInteractions(aiServiceClient, fileStorageService, persistenceService);
    }

    @Test
    void rejectsBlankTextContent() {
        when(inboxItemMapper.selectById(1L)).thenReturn(item("TEXT", "  "));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> analyzeService.analyze(1L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(aiServiceClient, persistenceService);
    }

    @Test
    void rejectsTextOverCharacterLimitBeforeCallingAi() {
        when(inboxItemMapper.selectById(1L)).thenReturn(item("TEXT", "x".repeat(20_001)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> analyzeService.analyze(1L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(aiServiceClient, persistenceService);
    }

    @Test
    void validatesAndNormalizesCompleteAnalysisBeforePersistence() {
        InboxItem original = item("TEXT", "原始正文");
        original.setTitle("学习笔记");
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        when(aiServiceClient.analyze("学习笔记", "原始正文")).thenReturn(
                new AiAnalyzeResponse(
                        "  新的摘要  ",
                        "技术学习",
                        List.of("Java", " java ", "Spring　AI"),
                        List.of(" ChatModel ", "chatmodel", "Tool　Calling"),
                        List.of(
                                new AiEntityResponse(" Spring　AI ", "TECHNOLOGY"),
                                new AiEntityResponse("spring AI", "TECHNOLOGY"),
                                new AiEntityResponse(" OpenAI ", "ORGANIZATION")
                        )
                )
        );
        InboxItem updated = item("TEXT", "原始正文");
        updated.setSummary("新的摘要");
        updated.setCategory("技术学习");
        updated.setTags(List.of("Java", "Spring AI"));
        updated.setKeywords(List.of("ChatModel", "Tool Calling"));
        updated.setEntities(List.of(
                new AiEntityResponse("Spring AI", "TECHNOLOGY"),
                new AiEntityResponse("OpenAI", "ORGANIZATION")
        ));
        List<NormalizedTag> expectedTags = List.of(
                new NormalizedTag("Java", "java"),
                new NormalizedTag("Spring AI", "spring ai")
        );
        when(persistenceService.replaceAnalysis(
                1L,
                "新的摘要",
                "技术学习",
                expectedTags,
                List.of("ChatModel", "Tool Calling"),
                List.of(
                        new NormalizedEntity("Spring AI", "TECHNOLOGY"),
                        new NormalizedEntity("OpenAI", "ORGANIZATION")
                )
        )).thenReturn(updated);

        InboxItem result = analyzeService.analyze(1L);

        assertEquals(updated, result);
        verify(aiServiceClient).analyze("学习笔记", "原始正文");
        verify(persistenceService).replaceAnalysis(
                1L,
                "新的摘要",
                "技术学习",
                expectedTags,
                List.of("ChatModel", "Tool Calling"),
                List.of(
                        new NormalizedEntity("Spring AI", "TECHNOLOGY"),
                        new NormalizedEntity("OpenAI", "ORGANIZATION")
                )
        );
    }

    @Test
    void rejectsCategoryOutsideFiniteSet() {
        prepareResponse(response("摘要", "Java后端", List.of("Java")));

        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        verifyPersistenceNeverStarted();
    }

    @Test
    void rejectsBlankOrOverlongSummary() {
        prepareResponse(response("  ", "其他", List.of("记录")));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(response("x".repeat(2_001), "其他", List.of("记录")));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        verifyPersistenceNeverStarted();
    }

    @Test
    void rejectsMissingOrTooManyTags() {
        prepareResponse(response("摘要", "其他", null));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(response("摘要", "其他", List.of()));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(response(
                "摘要",
                "其他",
                List.of("一", "二", "三", "四", "五", "六")
        ));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        verifyPersistenceNeverStarted();
    }

    @Test
    void rejectsBlankOrOverlongTag() {
        prepareResponse(response("摘要", "其他", List.of("  ")));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(response("摘要", "其他", List.of("x".repeat(65))));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        verifyPersistenceNeverStarted();
    }

    @Test
    void rejectsMissingBlankOverlongOrTooManyKeywords() {
        prepareResponse(new AiAnalyzeResponse("摘要", "其他", List.of("记录"), null, List.of()));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(new AiAnalyzeResponse(
                "摘要",
                "其他",
                List.of("记录"),
                List.of("一", "二", "三", "四", "五", "六", "七", "八", "九"),
                List.of()
        ));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(new AiAnalyzeResponse("摘要", "其他", List.of("记录"), List.of("  "), List.of()));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(new AiAnalyzeResponse(
                "摘要",
                "其他",
                List.of("记录"),
                List.of("x".repeat(65)),
                List.of()
        ));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        verifyPersistenceNeverStarted();
    }

    @Test
    void rejectsMissingInvalidOrTooManyEntities() {
        prepareResponse(new AiAnalyzeResponse("摘要", "其他", List.of("记录"), List.of(), null));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(new AiAnalyzeResponse(
                "摘要",
                "其他",
                List.of("记录"),
                List.of(),
                java.util.stream.IntStream.range(0, 11)
                        .mapToObj(index -> new AiEntityResponse("实体" + index, "OTHER"))
                        .toList()
        ));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(new AiAnalyzeResponse(
                "摘要",
                "其他",
                List.of("记录"),
                List.of(),
                List.of(new AiEntityResponse("  ", "OTHER"))
        ));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(new AiAnalyzeResponse(
                "摘要",
                "其他",
                List.of("记录"),
                List.of(),
                List.of(new AiEntityResponse("OpenAI", "COMPANY"))
        ));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        prepareResponse(new AiAnalyzeResponse(
                "摘要",
                "其他",
                List.of("记录"),
                List.of(),
                List.of(new AiEntityResponse("x".repeat(129), "OTHER"))
        ));
        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        verifyPersistenceNeverStarted();
    }

    @Test
    void allowsEmptyKeywordsAndEntitiesForVeryShortText() {
        InboxItem original = item("TEXT", "你好");
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        when(aiServiceClient.analyze(null, "你好")).thenReturn(
                new AiAnalyzeResponse("一句问候。", "其他", List.of("问候"), List.of(), List.of())
        );
        InboxItem updated = item("TEXT", "你好");
        when(persistenceService.replaceAnalysis(
                1L,
                "一句问候。",
                "其他",
                List.of(new NormalizedTag("问候", "问候")),
                List.of(),
                List.of()
        )).thenReturn(updated);

        assertEquals(updated, analyzeService.analyze(1L));
    }

    @Test
    void aiFailureKeepsOldAnalysisAndNeverStartsPersistence() {
        InboxItem original = item("TEXT", "原始正文");
        original.setSummary("旧摘要");
        original.setCategory("工作");
        original.setTags(List.of("旧标签"));
        original.setKeywords(List.of("旧关键词"));
        original.setEntities(List.of(new AiEntityResponse("旧实体", "OTHER")));
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        when(aiServiceClient.analyze(null, "原始正文"))
                .thenThrow(new AiServiceUnavailableException("mock AI failure"));

        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        assertEquals("旧摘要", original.getSummary());
        assertEquals("工作", original.getCategory());
        assertEquals(List.of("旧标签"), original.getTags());
        assertEquals(List.of("旧关键词"), original.getKeywords());
        assertEquals(List.of(new AiEntityResponse("旧实体", "OTHER")), original.getEntities());
        verifyNoInteractions(persistenceService);
    }

    @Test
    void urlExtractionFailureKeepsOldAnalysisAndNeverStartsPersistence() {
        InboxItem original = oldUrlAnalysis();
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        UrlAnalyzeException failure = UrlAnalyzeException.fromUpstream("URL_BLOCKED", 403)
                .orElseThrow();
        when(aiServiceClient.analyzeUrl("旧网页", "https://example.com/article"))
                .thenThrow(failure);

        assertThrows(UrlAnalyzeException.class, () -> analyzeService.analyze(1L));

        assertOldUrlAnalysis(original);
        verifyNoInteractions(persistenceService);
    }

    @Test
    void urlLlmFailureKeepsOldAnalysisAndNeverStartsPersistence() {
        InboxItem original = oldUrlAnalysis();
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        when(aiServiceClient.analyzeUrl("旧网页", "https://example.com/article"))
                .thenThrow(new AiServiceUnavailableException("mock LLM failure"));

        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        assertOldUrlAnalysis(original);
        verifyNoInteractions(persistenceService);
    }

    @Test
    void fileReadFailureKeepsOldAnalysisAndNeverStartsPersistence() {
        InboxItem original = oldFileAnalysis();
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        when(fileStorageService.loadForAnalysis(original.getFileUrl()))
                .thenThrow(FileAnalyzeException.fileNotFound());

        assertThrows(FileAnalyzeException.class, () -> analyzeService.analyze(1L));

        assertOldFileAnalysis(original);
        verifyNoInteractions(aiServiceClient, persistenceService);
    }

    @Test
    void fileExtractionFailureKeepsOldAnalysisAndNeverStartsPersistence() {
        InboxItem original = oldFileAnalysis();
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        Resource resource = new ByteArrayResource("pdf".getBytes()) {
            @Override
            public String getFilename() {
                return "00000000-0000-0000-0000-000000000001.pdf";
            }
        };
        when(fileStorageService.loadForAnalysis(original.getFileUrl())).thenReturn(
                new FileStorageService.AnalyzableFile(resource, MediaType.APPLICATION_PDF, 3)
        );
        FileAnalyzeException failure = FileAnalyzeException.fromUpstream(
                "FILE_PDF_NO_TEXT",
                422
        ).orElseThrow();
        when(aiServiceClient.analyzeFile("旧文档", resource, MediaType.APPLICATION_PDF))
                .thenThrow(failure);

        assertThrows(FileAnalyzeException.class, () -> analyzeService.analyze(1L));

        assertOldFileAnalysis(original);
        verifyNoInteractions(persistenceService);
    }

    @Test
    void fileLlmFailureKeepsOldAnalysisAndNeverStartsPersistence() {
        InboxItem original = oldFileAnalysis();
        when(inboxItemMapper.selectById(1L)).thenReturn(original);
        Resource resource = new ByteArrayResource("text".getBytes()) {
            @Override
            public String getFilename() {
                return "00000000-0000-0000-0000-000000000001.txt";
            }
        };
        when(fileStorageService.loadForAnalysis(original.getFileUrl())).thenReturn(
                new FileStorageService.AnalyzableFile(resource, MediaType.TEXT_PLAIN, 4)
        );
        when(aiServiceClient.analyzeFile("旧文档", resource, MediaType.TEXT_PLAIN))
                .thenThrow(new AiServiceUnavailableException("mock LLM failure"));

        assertThrows(AiServiceUnavailableException.class, () -> analyzeService.analyze(1L));

        assertOldFileAnalysis(original);
        verifyNoInteractions(persistenceService);
    }

    private AiAnalyzeResponse response(String summary, String category, List<String> tags) {
        return new AiAnalyzeResponse(summary, category, tags, List.of("关键词"), List.of());
    }

    private void verifyPersistenceNeverStarted() {
        verify(persistenceService, never()).replaceAnalysis(
                anyLong(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    private void prepareResponse(AiAnalyzeResponse response) {
        when(inboxItemMapper.selectById(1L)).thenReturn(item("TEXT", "正文"));
        when(aiServiceClient.analyze(null, "正文")).thenReturn(response);
    }

    private InboxItem item(String type, String content) {
        InboxItem item = new InboxItem();
        item.setId(1L);
        item.setType(type);
        item.setContent(content);
        return item;
    }

    private InboxItem oldUrlAnalysis() {
        InboxItem item = item("URL", null);
        item.setTitle("旧网页");
        item.setSourceUrl("https://example.com/article");
        item.setSummary("旧摘要");
        item.setCategory("资讯");
        item.setTags(List.of("旧标签"));
        item.setKeywords(List.of("旧关键词"));
        item.setEntities(List.of(new AiEntityResponse("旧实体", "OTHER")));
        return item;
    }

    private void assertOldUrlAnalysis(InboxItem item) {
        assertEquals("旧摘要", item.getSummary());
        assertEquals("资讯", item.getCategory());
        assertEquals(List.of("旧标签"), item.getTags());
        assertEquals(List.of("旧关键词"), item.getKeywords());
        assertEquals(List.of(new AiEntityResponse("旧实体", "OTHER")), item.getEntities());
    }

    private InboxItem oldFileAnalysis() {
        InboxItem item = item("FILE", null);
        item.setTitle("旧文档");
        item.setFileUrl("/api/files/00000000-0000-0000-0000-000000000001.pdf");
        item.setSummary("旧摘要");
        item.setCategory("学习成长");
        item.setTags(List.of("旧标签"));
        item.setKeywords(List.of("旧关键词"));
        item.setEntities(List.of(new AiEntityResponse("旧实体", "OTHER")));
        return item;
    }

    private void assertOldFileAnalysis(InboxItem item) {
        assertEquals("旧摘要", item.getSummary());
        assertEquals("学习成长", item.getCategory());
        assertEquals(List.of("旧标签"), item.getTags());
        assertEquals(List.of("旧关键词"), item.getKeywords());
        assertEquals(List.of(new AiEntityResponse("旧实体", "OTHER")), item.getEntities());
    }
}

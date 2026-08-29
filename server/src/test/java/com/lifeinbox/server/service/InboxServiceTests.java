package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.AiEntityResponse;
import com.lifeinbox.server.dto.AiRerankCandidate;
import com.lifeinbox.server.dto.AiRerankDocument;
import com.lifeinbox.server.dto.AiRerankResponse;
import com.lifeinbox.server.dto.AiSemanticSearchCandidate;
import com.lifeinbox.server.dto.AiSemanticSearchResponse;
import com.lifeinbox.server.dto.CreateInboxItemRequest;
import com.lifeinbox.server.entity.InboxEntity;
import com.lifeinbox.server.entity.AiProcessingStatus;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.mapper.InboxEntityMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import com.lifeinbox.server.mapper.InboxKeywordMapper;
import com.lifeinbox.server.mapper.InboxTagMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

class InboxServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final InboxTagMapper inboxTagMapper = mock(InboxTagMapper.class);
    private final InboxKeywordMapper inboxKeywordMapper = mock(InboxKeywordMapper.class);
    private final InboxEntityMapper inboxEntityMapper = mock(InboxEntityMapper.class);
    private final UrlMetadataService urlMetadataService = mock(UrlMetadataService.class);
    private final FileStorageService fileStorageService = mock(FileStorageService.class);
    private final InboxAnalysisStatusService analysisStatusService = mock(
            InboxAnalysisStatusService.class
    );
    private final ActionProcessingStatusService actionStatusService = mock(
            ActionProcessingStatusService.class
    );
    private final RelationProcessingStatusService relationStatusService = mock(
            RelationProcessingStatusService.class
    );
    private final InboxCapturePersistenceService capturePersistenceService = mock(
            InboxCapturePersistenceService.class
    );
    private final InboxVectorIndexScheduler vectorIndexScheduler = mock(
            InboxVectorIndexScheduler.class
    );
    private final AiServiceClient aiServiceClient = mock(AiServiceClient.class);
    private final RerankDocumentBuilder rerankDocumentBuilder = new RerankDocumentBuilder(
            new InboxSearchableContentService(inboxItemMapper)
    );
    private final InboxService inboxService = new InboxService(
            inboxItemMapper,
            inboxTagMapper,
            inboxKeywordMapper,
            inboxEntityMapper,
            urlMetadataService,
            fileStorageService,
            analysisStatusService,
            actionStatusService,
            relationStatusService,
            capturePersistenceService,
            vectorIndexScheduler,
            aiServiceClient,
            rerankDocumentBuilder,
            false
    );

    @Test
    void listReturnsAllAnalysisCollectionsForEachInboxItem() {
        InboxItem item = savedItem(1L, "TEXT", "标题", "正文", null);
        when(inboxItemMapper.selectList(any())).thenReturn(List.of(item));
        when(inboxTagMapper.selectTagNamesByInboxItemId(1L))
                .thenReturn(List.of("Java", "Spring AI"));
        when(inboxKeywordMapper.selectKeywordsByInboxItemId(1L))
                .thenReturn(List.of("ChatModel", "Tool Calling"));
        when(inboxEntityMapper.selectEntitiesByInboxItemId(1L)).thenReturn(List.of(
                entity("Spring AI", "TECHNOLOGY"),
                entity("OpenAI", "ORGANIZATION")
        ));
        when(analysisStatusService.isProcessingStale(item)).thenReturn(true);
        when(actionStatusService.isProcessingStale(item)).thenReturn(true);
        when(relationStatusService.isProcessingStale(item)).thenReturn(true);

        List<InboxItem> result = inboxService.list();

        assertEquals(List.of("Java", "Spring AI"), result.getFirst().getTags());
        assertEquals(List.of("ChatModel", "Tool Calling"), result.getFirst().getKeywords());
        assertEquals(List.of(
                new AiEntityResponse("Spring AI", "TECHNOLOGY"),
                new AiEntityResponse("OpenAI", "ORGANIZATION")
        ), result.getFirst().getEntities());
        assertTrue(result.getFirst().isAiProcessingStale());
        assertTrue(result.getFirst().isActionProcessingStale());
        assertTrue(result.getFirst().isRelationProcessingStale());
        verify(inboxTagMapper).selectTagNamesByInboxItemId(1L);
        verify(inboxKeywordMapper).selectKeywordsByInboxItemId(1L);
        verify(inboxEntityMapper).selectEntitiesByInboxItemId(1L);
        verify(analysisStatusService).isProcessingStale(item);
    }

    @Test
    void searchTrimsEscapesLikeWildcardsAndReturnsExistingInboxRepresentation() {
        InboxItem item = savedItem(1L, "TEXT", "CPU 使用率 50%", "路径 \\logs", null);
        when(inboxItemMapper.searchActiveByKeyword(
                "50%_!\\path",
                "50!%!_!!\\path",
                null,
                null,
                null,
                null
        )).thenReturn(List.of(item));
        when(inboxTagMapper.selectTagNamesByInboxItemId(1L)).thenReturn(List.of("性能"));
        when(inboxKeywordMapper.selectKeywordsByInboxItemId(1L)).thenReturn(List.of("CPU"));
        when(inboxEntityMapper.selectEntitiesByInboxItemId(1L)).thenReturn(List.of());

        List<InboxItem> result = inboxService.search("  50%_!\\path  ");

        assertEquals(List.of(item), result);
        assertEquals(List.of("性能"), result.getFirst().getTags());
        assertEquals(List.of("CPU"), result.getFirst().getKeywords());
        verify(inboxItemMapper).searchActiveByKeyword(
                "50%_!\\path",
                "50!%!_!!\\path",
                null,
                null,
                null,
                null
        );
        verify(analysisStatusService).isProcessingStale(item);
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    void searchReturnsEmptyListWhenKeywordHasNoMatch() {
        when(inboxItemMapper.searchActiveByKeyword(
                "missing", "missing", null, null, null, null
        ))
                .thenReturn(List.of());

        assertEquals(List.of(), inboxService.search("missing"));
    }

    @Test
    void searchReturnsExistingFieldMatchWhenAiMetadataIsMissing() {
        InboxItem item = savedItem(2L, "TEXT", "未分析标题", "仍可搜索的正文", null);
        when(inboxItemMapper.searchActiveByKeyword(
                "仍可搜索", "仍可搜索", null, null, null, null
        ))
                .thenReturn(List.of(item));
        when(inboxTagMapper.selectTagNamesByInboxItemId(2L)).thenReturn(List.of());
        when(inboxKeywordMapper.selectKeywordsByInboxItemId(2L)).thenReturn(List.of());
        when(inboxEntityMapper.selectEntitiesByInboxItemId(2L)).thenReturn(List.of());

        List<InboxItem> result = inboxService.search("仍可搜索");

        assertEquals(List.of(item), result);
        assertEquals(List.of(), result.getFirst().getTags());
        assertEquals(List.of(), result.getFirst().getKeywords());
        assertEquals(List.of(), result.getFirst().getEntities());
    }

    @Test
    void searchNormalizesAndCombinesAllOptionalFilters() {
        when(inboxItemMapper.searchActiveByKeyword(
                "Redis", "Redis", "URL", "技术", 1, null
        ))
                .thenReturn(List.of());

        assertEquals(List.of(), inboxService.search(" Redis ", " url ", " 技术 ", true));

        verify(inboxItemMapper).searchActiveByKeyword(
                "Redis", "Redis", "URL", "技术", 1, null
        );
    }

    @Test
    void searchPassesFavoriteFalseAsZero() {
        when(inboxItemMapper.searchActiveByKeyword(
                "Redis", "Redis", null, null, 0, null
        ))
                .thenReturn(List.of());

        assertEquals(List.of(), inboxService.search("Redis", " ", " ", false));

        verify(inboxItemMapper).searchActiveByKeyword(
                "Redis", "Redis", null, null, 0, null
        );
    }

    @Test
    void searchRejectsUnsupportedType() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.search("Redis", "AUDIO", null, null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(inboxItemMapper, never()).searchActiveByKeyword(
                any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void searchRejectsCategoryLongerThanDatabaseColumn() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.search("Redis", null, "分".repeat(33), null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(inboxItemMapper, never()).searchActiveByKeyword(
                any(), any(), any(), any(), any(), any()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void searchRejectsBlankQuery(String query) {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.search(query)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(inboxItemMapper, never()).searchActiveByKeyword(
                any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void searchRejectsQueryLongerThanTwoHundredCharacters() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.search("a".repeat(201))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(inboxItemMapper, never()).searchActiveByKeyword(
                any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void semanticSearchBatchResolvesFiltersAndRestoresQdrantOrder() {
        InboxItem first = savedItem(123L, "URL", "Redis 分布式锁", null, null);
        first.setStatus("ACTIVE");
        InboxItem second = savedItem(456L, "URL", "Lua 幂等", null, null);
        second.setStatus("ACTIVE");
        when(aiServiceClient.searchVectors("防止接口重复请求", 40)).thenReturn(
                new AiSemanticSearchResponse(List.of(
                        new AiSemanticSearchCandidate(123L, 0.91),
                        new AiSemanticSearchCandidate(456L, 0.82)
                ))
        );
        // SQL IN 不保证顺序，Service 必须恢复 Qdrant 排名。
        when(inboxItemMapper.selectActiveByIdsAndFilters(
                List.of(123L, 456L),
                "URL",
                "技术学习",
                1
        )).thenReturn(List.of(second, first));

        List<InboxItem> result = inboxService.search(
                " 防止接口重复请求 ",
                " url ",
                " 技术学习 ",
                true,
                "semantic",
                20
        );

        assertEquals(List.of(first, second), result);
        verify(inboxItemMapper).selectActiveByIdsAndFilters(
                List.of(123L, 456L),
                "URL",
                "技术学习",
                1
        );
        verify(inboxItemMapper, never()).selectById(any());
        verify(inboxItemMapper, never()).searchActiveByKeyword(
                any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void semanticSearchSafelyIgnoresDeletedAndArchivedCandidates() {
        InboxItem archived = savedItem(123L, "TEXT", "旧资料", "正文", null);
        archived.setStatus("ARCHIVED");
        when(aiServiceClient.searchVectors("旧资料", 40)).thenReturn(
                new AiSemanticSearchResponse(List.of(
                        new AiSemanticSearchCandidate(123L, 0.9),
                        new AiSemanticSearchCandidate(999L, 0.8)
                ))
        );
        // 999 已删除所以不在结果中；防御检查也拒绝意外返回的 ARCHIVED 123。
        when(inboxItemMapper.selectActiveByIdsAndFilters(
                List.of(123L, 999L), null, null, null
        )).thenReturn(List.of(archived));

        List<InboxItem> result = inboxService.search(
                "旧资料", null, null, null, "semantic", null
        );

        assertEquals(List.of(), result);
    }

    @Test
    void semanticSearchReturnsEmptyWithoutMysqlQueryWhenQdrantHasNoCandidates() {
        when(aiServiceClient.searchVectors("missing", 40)).thenReturn(
                new AiSemanticSearchResponse(List.of())
        );

        assertEquals(
                List.of(),
                inboxService.search("missing", null, null, null, "semantic", null)
        );

        verify(inboxItemMapper, never()).selectActiveByIdsAndFilters(any(), any(), any(), any());
    }

    @Test
    void searchRejectsInvalidModeAndAdvancedLimits() {
        ResponseStatusException invalidMode = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.search("Redis", null, null, null, "unsupported", null)
        );
        ResponseStatusException semanticLimit = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.search("Redis", null, null, null, "semantic", 51)
        );
        ResponseStatusException zeroHybridLimit = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.search("Redis", null, null, null, "hybrid", 0)
        );
        ResponseStatusException negativeHybridLimit = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.search("Redis", null, null, null, "hybrid", -1)
        );

        assertEquals(HttpStatus.BAD_REQUEST, invalidMode.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, semanticLimit.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, zeroHybridLimit.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, negativeHybridLimit.getStatusCode());
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    void hybridSearchUsesBoundedFilteredBranchesAndRrfOrdering() {
        InboxItem a = activeItem(101L, "URL", "Redis 防重复请求");
        InboxItem b = activeItem(102L, "URL", "Keyword only");
        InboxItem c = activeItem(103L, "URL", "Keyword tail");
        InboxItem d = activeItem(104L, "URL", "Semantic only");
        InboxItem e = activeItem(105L, "URL", "Semantic tail");

        when(inboxItemMapper.searchActiveByKeyword(
                "重复请求 Redis", "重复请求 Redis", "URL", "技术学习", 1, 6
        )).thenReturn(List.of(a, b, c));
        when(aiServiceClient.searchVectors("重复请求 Redis", 6)).thenReturn(
                new AiSemanticSearchResponse(List.of(
                        new AiSemanticSearchCandidate(101L, 0.95),
                        new AiSemanticSearchCandidate(104L, 0.91),
                        new AiSemanticSearchCandidate(105L, 0.87)
                ))
        );
        // MySQL IN 返回顺序与 Qdrant 无关，Service 先恢复 Semantic rank 再做 RRF。
        when(inboxItemMapper.selectActiveByIdsAndFilters(
                List.of(101L, 104L, 105L), "URL", "技术学习", 1
        )).thenReturn(List.of(e, d, a));

        List<InboxItem> result = inboxService.search(
                " 重复请求 Redis ",
                " url ",
                " 技术学习 ",
                true,
                "hybrid",
                3
        );

        assertEquals(List.of(a, b, d), result);
        verify(inboxItemMapper).searchActiveByKeyword(
                "重复请求 Redis", "重复请求 Redis", "URL", "技术学习", 1, 6
        );
        verify(inboxItemMapper).selectActiveByIdsAndFilters(
                List.of(101L, 104L, 105L), "URL", "技术学习", 1
        );
        verify(aiServiceClient, never()).rerank(any(), any(), anyInt());
    }

    @Test
    void enabledRerankReordersWiderRrfCandidatePoolBeforeFinalLimit() {
        InboxItem a = activeItem(501L, "TEXT", "Hybrid A");
        InboxItem b = activeItem(502L, "TEXT", "Hybrid B");
        InboxItem c = activeItem(503L, "TEXT", "Hybrid C");
        InboxItem d = activeItem(504L, "TEXT", "真正相关的接口幂等");
        when(inboxItemMapper.searchActiveByKeyword(
                "防止接口重复提交", "防止接口重复提交", "TEXT", "技术学习", 1, 4
        )).thenReturn(List.of(a, b, c, d));
        when(aiServiceClient.searchVectors("防止接口重复提交", 4)).thenReturn(
                new AiSemanticSearchResponse(List.of())
        );
        when(aiServiceClient.rerank(
                any(),
                any(),
                anyInt()
        )).thenReturn(new AiRerankResponse(List.of(
                new AiRerankCandidate(504L, 0.98),
                new AiRerankCandidate(501L, 0.7),
                new AiRerankCandidate(502L, 0.6),
                new AiRerankCandidate(503L, 0.5)
        )));

        List<InboxItem> result = rerankEnabledService().search(
                "防止接口重复提交",
                "TEXT",
                "技术学习",
                true,
                "hybrid",
                2
        );

        assertEquals(List.of(d, a), result);
        ArgumentCaptor<List<AiRerankDocument>> documents = rerankDocumentsCaptor();
        verify(aiServiceClient).rerank(
                org.mockito.ArgumentMatchers.eq("防止接口重复提交"),
                documents.capture(),
                org.mockito.ArgumentMatchers.eq(4)
        );
        assertEquals(
                List.of(501L, 502L, 503L, 504L),
                documents.getValue().stream().map(AiRerankDocument::id).toList()
        );
    }

    @Test
    void maximumFinalLimitUsesAtMostOneHundredRerankCandidates() {
        List<InboxItem> candidates = java.util.stream.LongStream.rangeClosed(1, 100)
                .mapToObj(id -> activeItem(1_000L + id, "TEXT", "Candidate " + id))
                .toList();
        when(inboxItemMapper.searchActiveByKeyword(
                "query", "query", null, null, null, 100
        )).thenReturn(candidates);
        when(aiServiceClient.searchVectors("query", 100)).thenReturn(
                new AiSemanticSearchResponse(List.of())
        );
        when(aiServiceClient.rerank(any(), any(), anyInt())).thenReturn(
                new AiRerankResponse(List.of())
        );

        List<InboxItem> result = rerankEnabledService().search(
                "query", null, null, null, "hybrid", 50
        );

        assertEquals(50, result.size());
        ArgumentCaptor<List<AiRerankDocument>> documents = rerankDocumentsCaptor();
        verify(aiServiceClient).rerank(any(), documents.capture(),
                org.mockito.ArgumentMatchers.eq(100));
        assertEquals(100, documents.getValue().size());
        verify(inboxItemMapper).searchActiveByKeyword(
                "query", "query", null, null, null, 100
        );
        verify(aiServiceClient).searchVectors("query", 100);
    }

    @Test
    void rerankKeepsOriginalOrderForTiesAndAppendsMissingCandidates() {
        InboxItem a = activeItem(601L, "TEXT", "A");
        InboxItem b = activeItem(602L, "TEXT", "B");
        InboxItem c = activeItem(603L, "TEXT", "C");
        when(inboxItemMapper.searchActiveByKeyword(
                "query", "query", null, null, null, 6
        )).thenReturn(List.of(a, b, c));
        when(aiServiceClient.searchVectors("query", 6)).thenReturn(
                new AiSemanticSearchResponse(List.of())
        );
        when(aiServiceClient.rerank(any(), any(), anyInt())).thenReturn(
                new AiRerankResponse(List.of(
                        new AiRerankCandidate(603L, 0.9),
                        new AiRerankCandidate(602L, 0.9)
                ))
        );

        List<InboxItem> result = rerankEnabledService().search(
                "query", null, null, null, "hybrid", 3
        );

        // 同分恢复原 RRF 次序 B、C；Provider 缺失的 A 随后按原顺序追加。
        assertEquals(List.of(b, c, a), result);
    }

    @Test
    void rerankFailureFallsBackToOriginalRrfOrder() {
        InboxItem a = activeItem(701L, "TEXT", "A");
        InboxItem b = activeItem(702L, "TEXT", "B");
        when(inboxItemMapper.searchActiveByKeyword(
                "query", "query", null, null, null, 4
        )).thenReturn(List.of(a, b));
        when(aiServiceClient.searchVectors("query", 4)).thenReturn(
                new AiSemanticSearchResponse(List.of())
        );
        when(aiServiceClient.rerank(any(), any(), anyInt())).thenThrow(
                new AiServiceUnavailableException("timeout")
        );

        List<InboxItem> result = rerankEnabledService().search(
                "query", null, null, null, "hybrid", 2
        );

        assertEquals(List.of(a, b), result);
    }

    @Test
    void invalidUnknownOrDuplicateRerankIdsCannotChangeCandidateSet() {
        InboxItem a = activeItem(801L, "TEXT", "A");
        InboxItem b = activeItem(802L, "TEXT", "B");
        when(inboxItemMapper.searchActiveByKeyword(
                "query", "query", null, null, null, 4
        )).thenReturn(List.of(a, b));
        when(aiServiceClient.searchVectors("query", 4)).thenReturn(
                new AiSemanticSearchResponse(List.of())
        );
        when(aiServiceClient.rerank(any(), any(), anyInt())).thenReturn(
                new AiRerankResponse(List.of(
                        new AiRerankCandidate(999L, 1.0),
                        new AiRerankCandidate(999L, 0.9)
                ))
        );

        List<InboxItem> result = rerankEnabledService().search(
                "query", null, null, null, "hybrid", 2
        );

        assertEquals(List.of(a, b), result);
        verify(inboxItemMapper, never()).selectById(999L);
    }

    @Test
    void rerankReceivesOnlyMysqlFilteredActiveCandidates() {
        InboxItem active = activeItem(901L, "URL", "当前资料");
        InboxItem archived = activeItem(902L, "URL", "归档资料");
        archived.setStatus("ARCHIVED");
        when(inboxItemMapper.searchActiveByKeyword(
                "资料", "资料", "URL", "技术学习", 1, 4
        )).thenReturn(List.of(active));
        when(aiServiceClient.searchVectors("资料", 4)).thenReturn(
                new AiSemanticSearchResponse(List.of(
                        new AiSemanticSearchCandidate(902L, 0.99),
                        new AiSemanticSearchCandidate(999L, 0.9)
                ))
        );
        when(inboxItemMapper.selectActiveByIdsAndFilters(
                List.of(902L, 999L), "URL", "技术学习", 1
        )).thenReturn(List.of(archived));
        when(aiServiceClient.rerank(any(), any(), anyInt())).thenReturn(
                new AiRerankResponse(List.of(new AiRerankCandidate(901L, 0.8)))
        );

        List<InboxItem> result = rerankEnabledService().search(
                "资料", "URL", "技术学习", true, "hybrid", 2
        );

        assertEquals(List.of(active), result);
        ArgumentCaptor<List<AiRerankDocument>> documents = rerankDocumentsCaptor();
        verify(aiServiceClient).rerank(any(), documents.capture(), anyInt());
        assertEquals(List.of(901L), documents.getValue().stream()
                .map(AiRerankDocument::id)
                .toList());
    }

    @Test
    void hybridSearchDegradesToKeywordWhenSemanticOrVectorStoreIsUnavailable() {
        InboxItem a = activeItem(201L, "TEXT", "Keyword A");
        InboxItem b = activeItem(202L, "TEXT", "Keyword B");
        when(inboxItemMapper.searchActiveByKeyword(
                "Redis", "Redis", null, null, null, 40
        )).thenReturn(List.of(a, b));
        when(aiServiceClient.searchVectors("Redis", 40)).thenThrow(
                new AiServiceUnavailableException("Vector Store 未启用")
        );

        List<InboxItem> result = inboxService.search(
                "Redis", null, null, null, "hybrid", null
        );

        assertEquals(List.of(a, b), result);
        verify(inboxItemMapper, never()).selectActiveByIdsAndFilters(
                any(), any(), any(), any()
        );
    }

    @Test
    void hybridSearchDegradesToSemanticWhenKeywordBranchFails() {
        InboxItem c = activeItem(301L, "TEXT", "Semantic C");
        InboxItem d = activeItem(302L, "TEXT", "Semantic D");
        when(inboxItemMapper.searchActiveByKeyword(
                "幂等", "幂等", null, null, null, 40
        )).thenThrow(new IllegalStateException("keyword unavailable"));
        when(aiServiceClient.searchVectors("幂等", 40)).thenReturn(
                new AiSemanticSearchResponse(List.of(
                        new AiSemanticSearchCandidate(301L, 0.9),
                        new AiSemanticSearchCandidate(302L, 0.8)
                ))
        );
        when(inboxItemMapper.selectActiveByIdsAndFilters(
                List.of(301L, 302L), null, null, null
        )).thenReturn(List.of(d, c));

        List<InboxItem> result = inboxService.search(
                "幂等", null, null, null, "hybrid", null
        );

        assertEquals(List.of(c, d), result);
    }

    @Test
    void hybridSearchReturnsControlledErrorWhenBothBranchesFail() {
        when(inboxItemMapper.searchActiveByKeyword(
                "Redis", "Redis", null, null, null, 40
        )).thenThrow(new IllegalStateException("keyword unavailable"));
        when(aiServiceClient.searchVectors("Redis", 40)).thenThrow(
                new AiServiceUnavailableException("semantic unavailable")
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.search("Redis", null, null, null, "hybrid", null)
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
    }

    @Test
    void hybridSearchReturnsNormalEmptyWhenBothSuccessfulBranchesHaveNoCandidates() {
        when(inboxItemMapper.searchActiveByKeyword(
                "missing", "missing", null, null, null, 40
        )).thenReturn(List.of());
        when(aiServiceClient.searchVectors("missing", 40)).thenReturn(
                new AiSemanticSearchResponse(List.of())
        );

        assertEquals(
                List.of(),
                inboxService.search("missing", null, null, null, "hybrid", null)
        );
        verify(inboxItemMapper, never()).selectActiveByIdsAndFilters(
                any(), any(), any(), any()
        );
    }

    @Test
    void hybridSearchIgnoresArchivedAndDeletedSemanticCandidates() {
        InboxItem keyword = activeItem(401L, "TEXT", "当前资料");
        InboxItem archived = activeItem(402L, "TEXT", "旧资料");
        archived.setStatus("ARCHIVED");
        when(inboxItemMapper.searchActiveByKeyword(
                "资料", "资料", null, null, null, 40
        )).thenReturn(List.of(keyword));
        when(aiServiceClient.searchVectors("资料", 40)).thenReturn(
                new AiSemanticSearchResponse(List.of(
                        new AiSemanticSearchCandidate(402L, 0.95),
                        new AiSemanticSearchCandidate(999L, 0.9)
                ))
        );
        when(inboxItemMapper.selectActiveByIdsAndFilters(
                List.of(402L, 999L), null, null, null
        )).thenReturn(List.of(archived));

        List<InboxItem> result = inboxService.search(
                "资料", null, null, null, "hybrid", null
        );

        assertEquals(List.of(keyword), result);
    }

    @Test
    void createTextKeepsExistingTextBehavior() {
        CreateInboxItemRequest request = createRequest("TEXT", "文字标题", "文字内容", null);
        InboxItem savedItem = savedItem(1L, "TEXT", "文字标题", "文字内容", null);
        prepareInsert(savedItem);

        InboxItem result = inboxService.create(request);

        assertEquals(savedItem, result);
        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(capturePersistenceService).save(captor.capture());
        assertEquals("TEXT", captor.getValue().getType());
        assertEquals("文字标题", captor.getValue().getTitle());
        assertEquals("文字内容", captor.getValue().getContent());
        assertEquals(null, captor.getValue().getSourceUrl());
        assertEquals(AiProcessingStatus.NOT_PROCESSED, captor.getValue().getAiStatus());
        verify(urlMetadataService, never()).resolveTitle(any());
    }

    @Test
    void createUrlStoresSourceUrlWithoutContent() {
        CreateInboxItemRequest request = createRequest(
                "URL",
                "OpenAI Java",
                null,
                " https://github.com/openai/openai-java "
        );
        InboxItem savedItem = savedItem(
                2L,
                "URL",
                "OpenAI Java",
                null,
                "https://github.com/openai/openai-java"
        );
        prepareInsert(savedItem);

        InboxItem result = inboxService.create(request);

        assertEquals(savedItem, result);
        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(capturePersistenceService).save(captor.capture());
        assertEquals("URL", captor.getValue().getType());
        assertEquals("OpenAI Java", captor.getValue().getTitle());
        assertEquals(null, captor.getValue().getContent());
        assertEquals("https://github.com/openai/openai-java", captor.getValue().getSourceUrl());
        assertEquals(AiProcessingStatus.NOT_PROCESSED, captor.getValue().getAiStatus());
        verify(urlMetadataService, never()).resolveTitle(any());
    }

    @Test
    void createUrlFetchesTitleWhenTitleIsBlank() {
        String sourceUrl = "https://github.com/openai/openai-java";
        CreateInboxItemRequest request = createRequest("URL", " ", null, sourceUrl);
        InboxItem savedItem = savedItem(3L, "URL", "openai/openai-java", null, sourceUrl);
        when(urlMetadataService.resolveTitle(sourceUrl)).thenReturn("openai/openai-java");
        prepareInsert(savedItem);

        InboxItem result = inboxService.create(request);

        assertEquals(savedItem, result);
        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(capturePersistenceService).save(captor.capture());
        assertEquals("openai/openai-java", captor.getValue().getTitle());
        verify(urlMetadataService).resolveTitle(sourceUrl);
    }

    @Test
    void createUrlRejectsMissingSourceUrl() {
        CreateInboxItemRequest request = createRequest("URL", null, null, " ");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.create(request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(capturePersistenceService, never()).save(any(InboxItem.class));
    }

    @Test
    void createUrlRejectsInvalidSourceUrl() {
        CreateInboxItemRequest request = createRequest("URL", null, null, "javascript:alert(1)");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.create(request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(capturePersistenceService, never()).save(any(InboxItem.class));
    }

    @Test
    void createTextRejectsMissingContent() {
        CreateInboxItemRequest request = createRequest("TEXT", null, " ", null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.create(request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(capturePersistenceService, never()).save(any(InboxItem.class));
    }

    @Test
    void createRejectsUnsupportedType() {
        CreateInboxItemRequest request = createRequest("IMAGE", null, null, null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.create(request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(capturePersistenceService, never()).save(any(InboxItem.class));
    }

    @Test
    void createFileUsesOriginalFilenameAndStoresFileUrl() {
        MultipartFile file = mock(MultipartFile.class);
        String storedName = "550e8400-e29b-41d4-a716-446655440000.pdf";
        when(fileStorageService.store(file)).thenReturn(
                new FileStorageService.StoredFile(storedName, "操作系统实验报告.pdf")
        );
        InboxItem savedItem = new InboxItem();
        savedItem.setId(4L);
        savedItem.setType("FILE");
        savedItem.setTitle("操作系统实验报告.pdf");
        savedItem.setFileUrl("/api/files/" + storedName);
        when(capturePersistenceService.save(any(InboxItem.class))).thenReturn(savedItem);

        InboxItem result = inboxService.createFile(file, " ");

        assertEquals(savedItem, result);
        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(capturePersistenceService).save(captor.capture());
        assertEquals("FILE", captor.getValue().getType());
        assertEquals("操作系统实验报告.pdf", captor.getValue().getTitle());
        assertEquals("/api/files/" + storedName, captor.getValue().getFileUrl());
        assertEquals("ACTIVE", captor.getValue().getStatus());
        assertEquals(0, captor.getValue().getFavorite());
        assertEquals(AiProcessingStatus.NOT_PROCESSED, captor.getValue().getAiStatus());
        verify(fileStorageService, never()).delete(storedName);
    }

    @Test
    void createFileDeletesStoredFileWhenDatabaseInsertFails() {
        MultipartFile file = mock(MultipartFile.class);
        String storedName = "550e8400-e29b-41d4-a716-446655440000.txt";
        when(fileStorageService.store(file)).thenReturn(
                new FileStorageService.StoredFile(storedName, "笔记.txt")
        );
        when(capturePersistenceService.save(any(InboxItem.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> inboxService.createFile(file, null));

        verify(fileStorageService).delete(storedName);
    }

    @Test
    void createImageStoresImageInboxItem() {
        MultipartFile image = mock(MultipartFile.class);
        String storedName = "550e8400-e29b-41d4-a716-446655440000.png";
        when(fileStorageService.storeImage(image)).thenReturn(
                new FileStorageService.StoredFile(storedName, "微信截图.png")
        );
        InboxItem savedItem = new InboxItem();
        savedItem.setId(5L);
        savedItem.setType("IMAGE");
        savedItem.setTitle("旅行照片");
        savedItem.setFileUrl("/api/files/" + storedName);
        when(capturePersistenceService.save(any(InboxItem.class))).thenReturn(savedItem);

        InboxItem result = inboxService.createImage(image, " 旅行照片 ");

        assertEquals(savedItem, result);
        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(capturePersistenceService).save(captor.capture());
        assertEquals("IMAGE", captor.getValue().getType());
        assertEquals("旅行照片", captor.getValue().getTitle());
        assertEquals("/api/files/" + storedName, captor.getValue().getFileUrl());
        assertEquals("ACTIVE", captor.getValue().getStatus());
        assertEquals(0, captor.getValue().getFavorite());
        assertEquals(AiProcessingStatus.NOT_PROCESSED, captor.getValue().getAiStatus());
        verify(fileStorageService, never()).delete(storedName);
    }

    @Test
    void createImageDeletesStoredImageWhenDatabaseInsertFails() {
        MultipartFile image = mock(MultipartFile.class);
        String storedName = "550e8400-e29b-41d4-a716-446655440000.jpg";
        when(fileStorageService.storeImage(image)).thenReturn(
                new FileStorageService.StoredFile(storedName, "照片.jpg")
        );
        when(capturePersistenceService.save(any(InboxItem.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> inboxService.createImage(image, null));

        verify(fileStorageService).delete(storedName);
    }

    @Test
    void deleteRemovesExistingTextItemWithoutTouchingFileStorage() {
        InboxItem item = savedItem(1L, "TEXT", "标题", "正文", null);
        when(inboxItemMapper.selectById(1L)).thenReturn(item);
        when(inboxItemMapper.deleteById(1L)).thenReturn(1);

        assertDoesNotThrow(() -> inboxService.delete(1L));

        verify(inboxItemMapper).deleteById(1L);
        verify(vectorIndexScheduler).scheduleDelete(1L);
        verify(fileStorageService, never()).deleteByFileUrl(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FILE", "IMAGE"})
    void deleteRemovesManagedFileAfterDeletingStoredItem(String type) {
        InboxItem item = savedItem(2L, type, "受管文件", null, null);
        item.setFileUrl("/api/files/550e8400-e29b-41d4-a716-446655440000.pdf");
        when(inboxItemMapper.selectById(2L)).thenReturn(item);
        when(inboxItemMapper.deleteById(2L)).thenReturn(1);

        assertDoesNotThrow(() -> inboxService.delete(2L));

        verify(inboxItemMapper).deleteById(2L);
        verify(vectorIndexScheduler).scheduleDelete(2L);
        verify(fileStorageService).deleteByFileUrl(item.getFileUrl());
    }

    @Test
    void deleteReturnsNotFoundWhenItemDoesNotExist() {
        when(inboxItemMapper.selectById(99L)).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.delete(99L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(inboxItemMapper, never()).deleteById(99L);
        verify(vectorIndexScheduler, never()).scheduleDelete(99L);
        verify(fileStorageService, never()).deleteByFileUrl(any());
    }

    @Test
    void archiveUpdatesExistingItemStatus() {
        InboxItem inboxItem = new InboxItem();
        inboxItem.setId(1L);
        inboxItem.setStatus("ACTIVE");
        when(inboxItemMapper.selectById(1L)).thenReturn(inboxItem);
        when(inboxItemMapper.updateInboxStatus(1L, "ARCHIVED")).thenReturn(1);

        assertDoesNotThrow(() -> inboxService.archive(1L));

        verify(inboxItemMapper).updateInboxStatus(1L, "ARCHIVED");
        verify(vectorIndexScheduler).scheduleDelete(1L);
    }

    @Test
    void archiveReturnsNotFoundWhenItemDoesNotExist() {
        when(inboxItemMapper.selectById(99L)).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.archive(99L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    @Test
    void favoriteUpdatesExistingItemWithoutChangingStatus() {
        InboxItem inboxItem = new InboxItem();
        inboxItem.setId(1L);
        inboxItem.setStatus("ACTIVE");
        inboxItem.setFavorite(0);
        when(inboxItemMapper.selectById(1L)).thenReturn(inboxItem);
        when(inboxItemMapper.updateFavorite(1L, 1)).thenReturn(1);

        assertDoesNotThrow(() -> inboxService.favorite(1L));

        assertEquals("ACTIVE", inboxItem.getStatus());
        verify(inboxItemMapper).updateFavorite(1L, 1);
        verifyNoInteractions(vectorIndexScheduler);
    }

    @Test
    void unfavoriteUpdatesExistingItemWithoutChangingStatus() {
        InboxItem inboxItem = new InboxItem();
        inboxItem.setId(1L);
        inboxItem.setStatus("ACTIVE");
        inboxItem.setFavorite(1);
        when(inboxItemMapper.selectById(1L)).thenReturn(inboxItem);
        when(inboxItemMapper.updateFavorite(1L, 0)).thenReturn(1);

        assertDoesNotThrow(() -> inboxService.unfavorite(1L));

        assertEquals("ACTIVE", inboxItem.getStatus());
        verify(inboxItemMapper).updateFavorite(1L, 0);
    }

    @Test
    void favoriteReturnsNotFoundWhenItemDoesNotExist() {
        when(inboxItemMapper.selectById(99L)).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.favorite(99L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    @Test
    void unfavoriteReturnsNotFoundWhenItemDoesNotExist() {
        when(inboxItemMapper.selectById(99L)).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.unfavorite(99L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    private CreateInboxItemRequest createRequest(
            String type,
            String title,
            String content,
            String sourceUrl
    ) {
        CreateInboxItemRequest request = new CreateInboxItemRequest();
        request.setType(type);
        request.setTitle(title);
        request.setContent(content);
        request.setSourceUrl(sourceUrl);
        return request;
    }

    private InboxItem savedItem(
            Long id,
            String type,
            String title,
            String content,
            String sourceUrl
    ) {
        InboxItem inboxItem = new InboxItem();
        inboxItem.setId(id);
        inboxItem.setType(type);
        inboxItem.setTitle(title);
        inboxItem.setContent(content);
        inboxItem.setSourceUrl(sourceUrl);
        return inboxItem;
    }

    private InboxItem activeItem(Long id, String type, String title) {
        InboxItem item = savedItem(id, type, title, null, null);
        item.setStatus("ACTIVE");
        return item;
    }

    private InboxService rerankEnabledService() {
        return new InboxService(
                inboxItemMapper,
                inboxTagMapper,
                inboxKeywordMapper,
                inboxEntityMapper,
                urlMetadataService,
                fileStorageService,
                analysisStatusService,
                actionStatusService,
                relationStatusService,
                capturePersistenceService,
                vectorIndexScheduler,
                aiServiceClient,
                rerankDocumentBuilder,
                true
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<AiRerankDocument>> rerankDocumentsCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private void prepareInsert(InboxItem savedItem) {
        when(capturePersistenceService.save(any(InboxItem.class))).thenReturn(savedItem);
    }

    private InboxEntity entity(String name, String type) {
        InboxEntity entity = new InboxEntity();
        entity.setName(name);
        entity.setType(type);
        return entity;
    }
}

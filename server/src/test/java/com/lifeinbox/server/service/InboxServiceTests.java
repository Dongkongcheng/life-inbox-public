package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.AiEntityResponse;
import com.lifeinbox.server.dto.CreateInboxItemRequest;
import com.lifeinbox.server.entity.InboxEntity;
import com.lifeinbox.server.entity.AiProcessingStatus;
import com.lifeinbox.server.entity.InboxItem;
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
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

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
    private final InboxCapturePersistenceService capturePersistenceService = mock(
            InboxCapturePersistenceService.class
    );
    private final InboxService inboxService = new InboxService(
            inboxItemMapper,
            inboxTagMapper,
            inboxKeywordMapper,
            inboxEntityMapper,
            urlMetadataService,
            fileStorageService,
            analysisStatusService,
            capturePersistenceService
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

        List<InboxItem> result = inboxService.list();

        assertEquals(List.of("Java", "Spring AI"), result.getFirst().getTags());
        assertEquals(List.of("ChatModel", "Tool Calling"), result.getFirst().getKeywords());
        assertEquals(List.of(
                new AiEntityResponse("Spring AI", "TECHNOLOGY"),
                new AiEntityResponse("OpenAI", "ORGANIZATION")
        ), result.getFirst().getEntities());
        assertTrue(result.getFirst().isAiProcessingStale());
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
                null
        );
        verify(analysisStatusService).isProcessingStale(item);
    }

    @Test
    void searchReturnsEmptyListWhenKeywordHasNoMatch() {
        when(inboxItemMapper.searchActiveByKeyword("missing", "missing", null, null, null))
                .thenReturn(List.of());

        assertEquals(List.of(), inboxService.search("missing"));
    }

    @Test
    void searchReturnsExistingFieldMatchWhenAiMetadataIsMissing() {
        InboxItem item = savedItem(2L, "TEXT", "未分析标题", "仍可搜索的正文", null);
        when(inboxItemMapper.searchActiveByKeyword("仍可搜索", "仍可搜索", null, null, null))
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
        when(inboxItemMapper.searchActiveByKeyword("Redis", "Redis", "URL", "技术", 1))
                .thenReturn(List.of());

        assertEquals(List.of(), inboxService.search(" Redis ", " url ", " 技术 ", true));

        verify(inboxItemMapper).searchActiveByKeyword("Redis", "Redis", "URL", "技术", 1);
    }

    @Test
    void searchPassesFavoriteFalseAsZero() {
        when(inboxItemMapper.searchActiveByKeyword("Redis", "Redis", null, null, 0))
                .thenReturn(List.of());

        assertEquals(List.of(), inboxService.search("Redis", " ", " ", false));

        verify(inboxItemMapper).searchActiveByKeyword("Redis", "Redis", null, null, 0);
    }

    @Test
    void searchRejectsUnsupportedType() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.search("Redis", "AUDIO", null, null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(inboxItemMapper, never()).searchActiveByKeyword(any(), any(), any(), any(), any());
    }

    @Test
    void searchRejectsCategoryLongerThanDatabaseColumn() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.search("Redis", null, "分".repeat(33), null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(inboxItemMapper, never()).searchActiveByKeyword(any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void searchRejectsBlankQuery(String query) {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.search(query)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(inboxItemMapper, never()).searchActiveByKeyword(any(), any(), any(), any(), any());
    }

    @Test
    void searchRejectsQueryLongerThanTwoHundredCharacters() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.search("a".repeat(201))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(inboxItemMapper, never()).searchActiveByKeyword(any(), any(), any(), any(), any());
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

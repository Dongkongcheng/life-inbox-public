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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private final InboxService inboxService = new InboxService(
            inboxItemMapper,
            inboxTagMapper,
            inboxKeywordMapper,
            inboxEntityMapper,
            urlMetadataService,
            fileStorageService
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

        List<InboxItem> result = inboxService.list();

        assertEquals(List.of("Java", "Spring AI"), result.getFirst().getTags());
        assertEquals(List.of("ChatModel", "Tool Calling"), result.getFirst().getKeywords());
        assertEquals(List.of(
                new AiEntityResponse("Spring AI", "TECHNOLOGY"),
                new AiEntityResponse("OpenAI", "ORGANIZATION")
        ), result.getFirst().getEntities());
        verify(inboxTagMapper).selectTagNamesByInboxItemId(1L);
        verify(inboxKeywordMapper).selectKeywordsByInboxItemId(1L);
        verify(inboxEntityMapper).selectEntitiesByInboxItemId(1L);
    }

    @Test
    void createTextKeepsExistingTextBehavior() {
        CreateInboxItemRequest request = createRequest("TEXT", "文字标题", "文字内容", null);
        InboxItem savedItem = savedItem(1L, "TEXT", "文字标题", "文字内容", null);
        prepareInsert(savedItem);

        InboxItem result = inboxService.create(request);

        assertEquals(savedItem, result);
        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxItemMapper).insert(captor.capture());
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
        verify(inboxItemMapper).insert(captor.capture());
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
        verify(inboxItemMapper).insert(captor.capture());
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
        verify(inboxItemMapper, never()).insert(any(InboxItem.class));
    }

    @Test
    void createUrlRejectsInvalidSourceUrl() {
        CreateInboxItemRequest request = createRequest("URL", null, null, "javascript:alert(1)");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.create(request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(inboxItemMapper, never()).insert(any(InboxItem.class));
    }

    @Test
    void createTextRejectsMissingContent() {
        CreateInboxItemRequest request = createRequest("TEXT", null, " ", null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.create(request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(inboxItemMapper, never()).insert(any(InboxItem.class));
    }

    @Test
    void createRejectsUnsupportedType() {
        CreateInboxItemRequest request = createRequest("IMAGE", null, null, null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.create(request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(inboxItemMapper, never()).insert(any(InboxItem.class));
    }

    @Test
    void createFileUsesOriginalFilenameAndStoresFileUrl() {
        MultipartFile file = mock(MultipartFile.class);
        String storedName = "550e8400-e29b-41d4-a716-446655440000.pdf";
        when(fileStorageService.store(file)).thenReturn(
                new FileStorageService.StoredFile(storedName, "操作系统实验报告.pdf")
        );
        when(inboxItemMapper.insert(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem itemToInsert = invocation.getArgument(0);
            itemToInsert.setId(4L);
            return 1;
        });
        InboxItem savedItem = new InboxItem();
        savedItem.setId(4L);
        savedItem.setType("FILE");
        savedItem.setTitle("操作系统实验报告.pdf");
        savedItem.setFileUrl("/api/files/" + storedName);
        when(inboxItemMapper.selectById(4L)).thenReturn(savedItem);

        InboxItem result = inboxService.createFile(file, " ");

        assertEquals(savedItem, result);
        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxItemMapper).insert(captor.capture());
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
        when(inboxItemMapper.insert(any(InboxItem.class)))
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
        when(inboxItemMapper.insert(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem itemToInsert = invocation.getArgument(0);
            itemToInsert.setId(5L);
            return 1;
        });
        InboxItem savedItem = new InboxItem();
        savedItem.setId(5L);
        savedItem.setType("IMAGE");
        savedItem.setTitle("旅行照片");
        savedItem.setFileUrl("/api/files/" + storedName);
        when(inboxItemMapper.selectById(5L)).thenReturn(savedItem);

        InboxItem result = inboxService.createImage(image, " 旅行照片 ");

        assertEquals(savedItem, result);
        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxItemMapper).insert(captor.capture());
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
        when(inboxItemMapper.insert(any(InboxItem.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> inboxService.createImage(image, null));

        verify(fileStorageService).delete(storedName);
    }

    @Test
    void deleteRemovesExistingItem() {
        when(inboxItemMapper.deleteById(1L)).thenReturn(1);

        assertDoesNotThrow(() -> inboxService.delete(1L));

        verify(inboxItemMapper).deleteById(1L);
    }

    @Test
    void deleteReturnsNotFoundWhenItemDoesNotExist() {
        when(inboxItemMapper.deleteById(99L)).thenReturn(0);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> inboxService.delete(99L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
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
        when(inboxItemMapper.insert(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem itemToInsert = invocation.getArgument(0);
            itemToInsert.setId(savedItem.getId());
            return 1;
        });
        when(inboxItemMapper.selectById(savedItem.getId())).thenReturn(savedItem);
    }

    private InboxEntity entity(String name, String type) {
        InboxEntity entity = new InboxEntity();
        entity.setName(name);
        entity.setType(type);
        return entity;
    }
}

package com.lifeinbox.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileStorageServiceTests {

    @TempDir
    Path tempDirectory;

    @Test
    void storesFileWithUuidNameAndKeepsOriginalFilename() throws Exception {
        FileStorageService service = new FileStorageService(tempDirectory.toString());
        byte[] content = "LifeInbox file capture".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "学习笔记.TXT",
                "text/plain",
                content
        );

        FileStorageService.StoredFile storedFile = service.store(file);

        assertEquals("学习笔记.TXT", storedFile.originalFilename());
        assertTrue(storedFile.storedName().matches(
                "[0-9a-f-]{36}\\.txt"
        ));
        assertArrayEquals(content, Files.readAllBytes(tempDirectory.resolve(storedFile.storedName())));

        Resource resource = service.loadAsResource(storedFile.storedName());
        assertTrue(resource.exists());
    }

    @Test
    void rejectsEmptyFile() {
        FileStorageService service = new FileStorageService(tempDirectory.toString());
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.store(file)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectsFileLargerThanTwentyMegabytes() {
        FileStorageService service = new FileStorageService(tempDirectory.toString());
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(FileStorageService.MAX_FILE_SIZE + 1);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.store(file)
        );

        assertEquals(HttpStatus.CONTENT_TOO_LARGE, exception.getStatusCode());
    }

    @Test
    void rejectsDisallowedImageExtension() {
        FileStorageService service = new FileStorageService(tempDirectory.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "photo.png",
                "image/png",
                new byte[]{1}
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.store(file)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectsOriginalFilenameContainingPathTraversal() {
        FileStorageService service = new FileStorageService(tempDirectory.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "../secret.txt",
                "text/plain",
                new byte[]{1}
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.store(file)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectsMismatchedContentType() {
        FileStorageService service = new FileStorageService(tempDirectory.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "report.pdf",
                "image/png",
                new byte[]{1}
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.store(file)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectsUnsafeStoredNameWhenLoading() {
        FileStorageService service = new FileStorageService(tempDirectory.toString());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.loadAsResource("../application.yaml")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void returnsNotFoundForMissingStoredFile() {
        FileStorageService service = new FileStorageService(tempDirectory.toString());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.loadAsResource("550e8400-e29b-41d4-a716-446655440000.pdf")
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }
}

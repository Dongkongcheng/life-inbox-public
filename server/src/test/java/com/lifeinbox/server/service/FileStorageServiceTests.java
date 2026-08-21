package com.lifeinbox.server.service;

import com.lifeinbox.server.exception.FileAnalyzeException;
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
    void storesImageWithUuidNameAndImageMediaType() throws Exception {
        FileStorageService service = new FileStorageService(tempDirectory.toString());
        byte[] pngContent = new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x00
        };
        MockMultipartFile image = new MockMultipartFile(
                "file",
                "微信截图.png",
                "image/png",
                pngContent
        );

        FileStorageService.StoredFile storedImage = service.storeImage(image);

        assertEquals("微信截图.png", storedImage.originalFilename());
        assertTrue(storedImage.storedName().matches("[0-9a-f-]{36}\\.png"));
        assertEquals("image/png", service.mediaTypeFor(storedImage.storedName()).toString());
        assertArrayEquals(
                pngContent,
                Files.readAllBytes(tempDirectory.resolve(storedImage.storedName()))
        );
    }

    @Test
    void rejectsNonImageExtensionFromImageUpload() {
        FileStorageService service = new FileStorageService(tempDirectory.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "not an image".getBytes()
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.storeImage(file)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectsImageWithMismatchedContentType() {
        FileStorageService service = new FileStorageService(tempDirectory.toString());
        MockMultipartFile image = new MockMultipartFile(
                "file",
                "photo.png",
                "text/plain",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.storeImage(image)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectsImageWithInvalidSignature() {
        FileStorageService service = new FileStorageService(tempDirectory.toString());
        MockMultipartFile image = new MockMultipartFile(
                "file",
                "fake.png",
                "image/png",
                "not really a png".getBytes()
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.storeImage(image)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectsImageLargerThanTenMegabytes() {
        FileStorageService service = new FileStorageService(tempDirectory.toString());
        MultipartFile image = mock(MultipartFile.class);
        when(image.isEmpty()).thenReturn(false);
        when(image.getSize()).thenReturn(FileStorageService.MAX_IMAGE_SIZE + 1);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.storeImage(image)
        );

        assertEquals(HttpStatus.CONTENT_TOO_LARGE, exception.getStatusCode());
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

    @Test
    void safelyLoadsManagedTxtFileForAnalysis() throws Exception {
        FileStorageService service = new FileStorageService(tempDirectory.toString());
        String storedName = "550e8400-e29b-41d4-a716-446655440000.txt";
        byte[] content = "LifeInbox analyze".getBytes();
        Files.write(tempDirectory.resolve(storedName), content);

        FileStorageService.AnalyzableFile file = service.loadForAnalysis(
                "/api/files/" + storedName
        );

        assertEquals(storedName, file.resource().getFilename());
        assertEquals("text/plain", file.mediaType().toString());
        assertEquals(content.length, file.size());
        assertArrayEquals(content, file.resource().getInputStream().readAllBytes());
    }

    @Test
    void fileAnalysisRejectsMissingAndUnsafeManagedUrls() {
        FileStorageService service = new FileStorageService(tempDirectory.toString());

        FileAnalyzeException missing = assertThrows(
                FileAnalyzeException.class,
                () -> service.loadForAnalysis(
                        "/api/files/550e8400-e29b-41d4-a716-446655440000.pdf"
                )
        );
        FileAnalyzeException unsafe = assertThrows(
                FileAnalyzeException.class,
                () -> service.loadForAnalysis("/api/files/../application.yaml")
        );

        assertEquals("FILE_NOT_FOUND", missing.getCode());
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());
        assertEquals("FILE_NOT_FOUND", unsafe.getCode());
    }

    @Test
    void fileAnalysisRejectsUnsupportedStoredExtension() throws Exception {
        FileStorageService service = new FileStorageService(tempDirectory.toString());
        String storedName = "550e8400-e29b-41d4-a716-446655440000.docx";
        Files.write(tempDirectory.resolve(storedName), new byte[]{1});

        FileAnalyzeException exception = assertThrows(
                FileAnalyzeException.class,
                () -> service.loadForAnalysis("/api/files/" + storedName)
        );

        assertEquals("FILE_TYPE_UNSUPPORTED", exception.getCode());
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception.getStatus());
    }

    @Test
    void fileAnalysisRejectsFileOverTenMegabytes() throws Exception {
        FileStorageService service = new FileStorageService(tempDirectory.toString());
        String storedName = "550e8400-e29b-41d4-a716-446655440000.pdf";
        Files.write(
                tempDirectory.resolve(storedName),
                new byte[(int) FileStorageService.MAX_AI_ANALYZE_FILE_SIZE + 1]
        );

        FileAnalyzeException exception = assertThrows(
                FileAnalyzeException.class,
                () -> service.loadForAnalysis("/api/files/" + storedName)
        );

        assertEquals("FILE_TOO_LARGE", exception.getCode());
        assertEquals(HttpStatus.CONTENT_TOO_LARGE, exception.getStatus());
    }
}

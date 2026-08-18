package com.lifeinbox.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class FileStorageService {

    static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(FileStorageService.class);
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "txt", "md", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "zip"
    );
    private static final Map<String, Set<String>> ALLOWED_CONTENT_TYPES = Map.ofEntries(
            Map.entry("pdf", Set.of("application/pdf")),
            Map.entry("txt", Set.of("text/plain")),
            Map.entry("md", Set.of("text/markdown", "text/plain")),
            Map.entry("doc", Set.of("application/msword")),
            Map.entry("docx", Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/zip"
            )),
            Map.entry("ppt", Set.of("application/vnd.ms-powerpoint")),
            Map.entry("pptx", Set.of(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/zip"
            )),
            Map.entry("xls", Set.of("application/vnd.ms-excel")),
            Map.entry("xlsx", Set.of(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/zip"
            )),
            Map.entry("zip", Set.of("application/zip", "application/x-zip-compressed"))
    );
    private static final Pattern STORED_NAME_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\."
                    + "(?:pdf|txt|md|doc|docx|ppt|pptx|xls|xlsx|zip)$"
    );

    private final Path uploadDirectory;

    public FileStorageService(@Value("${life-inbox.storage.upload-dir:uploads}") String uploadDirectory) {
        Path configuredPath = Path.of(uploadDirectory);
        this.uploadDirectory = (configuredPath.isAbsolute()
                ? configuredPath
                : Path.of("").toAbsolutePath().resolve(configuredPath)).normalize();
    }

    public StoredFile store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "文件大小不能超过 20MB");
        }

        String originalFilename = validateOriginalFilename(file.getOriginalFilename());
        String extension = extensionOf(originalFilename);
        validateContentType(extension, file.getContentType());

        String storedName = UUID.randomUUID() + "." + extension;
        Path destination = resolveStoredPath(storedName);

        try {
            Files.createDirectories(uploadDirectory);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, destination);
            }
            return new StoredFile(storedName, originalFilename);
        } catch (IOException exception) {
            deletePathQuietly(destination);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "文件保存失败",
                    exception
            );
        }
    }

    public Resource loadAsResource(String storedName) {
        Path filePath = resolveStoredPath(storedName);
        if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        }
        return new FileSystemResource(filePath);
    }

    public MediaType mediaTypeFor(String storedName) {
        String extension = extensionOf(storedName);
        return switch (extension) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "txt", "md" -> MediaType.TEXT_PLAIN;
            case "zip" -> MediaType.parseMediaType("application/zip");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    public void delete(String storedName) {
        Path filePath = resolveStoredPath(storedName);
        deletePathQuietly(filePath);
    }

    private String validateOriginalFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原始文件名不能为空");
        }

        String trimmedFilename = originalFilename.trim();
        if (trimmedFilename.contains("/")
                || trimmedFilename.contains("\\")
                || trimmedFilename.equals(".")
                || trimmedFilename.equals("..")
                || trimmedFilename.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原始文件名不合法");
        }
        return trimmedFilename;
    }

    private String extensionOf(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == filename.length() - 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件缺少允许的扩展名");
        }

        String extension = filename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持该文件类型");
        }
        return extension;
    }

    private void validateContentType(String extension, String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return;
        }

        String normalizedContentType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(normalizedContentType)) {
            return;
        }

        Set<String> allowedTypes = ALLOWED_CONTENT_TYPES.get(extension);
        if (allowedTypes == null || !allowedTypes.contains(normalizedContentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件扩展名与 Content-Type 不匹配");
        }
    }

    private Path resolveStoredPath(String storedName) {
        if (storedName == null || !STORED_NAME_PATTERN.matcher(storedName).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名不合法");
        }

        Path resolvedPath = uploadDirectory.resolve(storedName).normalize();
        if (!resolvedPath.startsWith(uploadDirectory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件路径不合法");
        }
        return resolvedPath;
    }

    private void deletePathQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOGGER.warn("Unable to delete uploaded file {}", path, exception);
        }
    }

    public record StoredFile(String storedName, String originalFilename) {
    }
}

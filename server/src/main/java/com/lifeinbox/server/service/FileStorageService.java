package com.lifeinbox.server.service;

import com.lifeinbox.server.exception.FileAnalyzeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ByteArrayResource;
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
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * FILE 与 IMAGE 共用的本地存储组件，集中处理类型、大小、命名和路径安全校验。
 */
@Service
public class FileStorageService {

    static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;
    static final long MAX_AI_ANALYZE_FILE_SIZE = 10L * 1024 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(FileStorageService.class);
    private static final Set<String> FILE_EXTENSIONS = Set.of(
            "pdf", "txt", "md", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "zip"
    );
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "gif", "bmp"
    );
    private static final Set<String> AI_ANALYZE_EXTENSIONS = Set.of("txt", "md", "pdf");
    private static final String FILE_URL_PREFIX = "/api/files/";
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
            Map.entry("zip", Set.of("application/zip", "application/x-zip-compressed")),
            Map.entry("jpg", Set.of("image/jpeg")),
            Map.entry("jpeg", Set.of("image/jpeg")),
            Map.entry("png", Set.of("image/png")),
            Map.entry("webp", Set.of("image/webp")),
            Map.entry("gif", Set.of("image/gif")),
            Map.entry("bmp", Set.of("image/bmp", "image/x-ms-bmp"))
    );
    private static final Pattern STORED_NAME_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\."
                    + "(?:pdf|txt|md|doc|docx|ppt|pptx|xls|xlsx|zip|jpg|jpeg|png|webp|gif|bmp)$"
    );

    private final Path uploadDirectory;

    public FileStorageService(@Value("${life-inbox.storage.upload-dir:uploads}") String uploadDirectory) {
        Path configuredPath = Path.of(uploadDirectory);
        // 相对路径以后端启动目录为基准，统一转成绝对路径后再做边界校验。
        this.uploadDirectory = (configuredPath.isAbsolute()
                ? configuredPath
                : Path.of("").toAbsolutePath().resolve(configuredPath)).normalize();
    }

    public StoredFile store(MultipartFile file) {
        return store(file, FILE_EXTENSIONS, MAX_FILE_SIZE, "文件", true, false);
    }

    public StoredFile storeImage(MultipartFile file) {
        return store(file, IMAGE_EXTENSIONS, MAX_IMAGE_SIZE, "图片", false, true);
    }

    private StoredFile store(
            MultipartFile file,
            Set<String> allowedExtensions,
            long maxSize,
            String itemName,
            boolean allowOctetStream,
            boolean validateImageContent
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, itemName + "不能为空");
        }
        if (file.getSize() > maxSize) {
            String maxSizeText = maxSize == MAX_IMAGE_SIZE ? "10MB" : "20MB";
            throw new ResponseStatusException(
                    HttpStatus.CONTENT_TOO_LARGE,
                    itemName + "大小不能超过 " + maxSizeText
            );
        }

        String originalFilename = validateOriginalFilename(file.getOriginalFilename());
        String extension = extensionOf(originalFilename);
        if (!allowedExtensions.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持该" + itemName + "类型");
        }
        validateContentType(extension, file.getContentType(), allowOctetStream);
        if (validateImageContent) {
            // Content-Type 来自客户端，图片再检查常见文件头，降低伪装上传风险。
            validateImageSignature(file, extension);
        }

        // 磁盘名不使用原文件名，避免重名覆盖、特殊字符和路径穿越问题。
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
        // 禁止跟随符号链接，避免合法文件名最终指向 uploads 之外。
        Path filePath = resolveStoredPath(storedName);
        if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        }
        return new FileSystemResource(filePath);
    }

    /**
     * 从 Java 管理的 fileUrl 安全读取可分析文件。
     * 返回内存 Resource 而不是本地路径，避免 Python 与 Java 磁盘目录耦合。
     */
    public AnalyzableFile loadForAnalysis(String fileUrl) {
        String storedName = storedNameFromFileUrl(fileUrl);
        String extension = extensionOf(storedName);
        if (!AI_ANALYZE_EXTENSIONS.contains(extension)) {
            throw FileAnalyzeException.typeUnsupported();
        }

        Path filePath;
        try {
            filePath = resolveStoredPath(storedName);
        } catch (ResponseStatusException exception) {
            throw FileAnalyzeException.fileNotFound();
        }
        if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
            throw FileAnalyzeException.fileNotFound();
        }

        byte[] content;
        try (InputStream inputStream = Files.newInputStream(
                filePath,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        )) {
            content = inputStream.readNBytes((int) MAX_AI_ANALYZE_FILE_SIZE + 1);
        } catch (IOException | UnsupportedOperationException exception) {
            throw FileAnalyzeException.fileReadFailed();
        }
        if (content.length > MAX_AI_ANALYZE_FILE_SIZE) {
            throw FileAnalyzeException.fileTooLarge();
        }

        Resource resource = new NamedByteArrayResource(content, storedName);
        return new AnalyzableFile(resource, mediaTypeFor(storedName), content.length);
    }

    public MediaType mediaTypeFor(String storedName) {
        String extension = extensionOf(storedName);
        return switch (extension) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "txt", "md" -> MediaType.TEXT_PLAIN;
            case "zip" -> MediaType.parseMediaType("application/zip");
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "bmp" -> MediaType.parseMediaType("image/bmp");
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
        return extension;
    }

    private String storedNameFromFileUrl(String fileUrl) {
        if (fileUrl == null || !fileUrl.startsWith(FILE_URL_PREFIX)) {
            throw FileAnalyzeException.fileNotFound();
        }
        String storedName = fileUrl.substring(FILE_URL_PREFIX.length());
        if (storedName.contains("/") || storedName.contains("\\")) {
            throw FileAnalyzeException.fileNotFound();
        }
        return storedName;
    }

    private void validateContentType(String extension, String contentType, boolean allowOctetStream) {
        if (contentType == null || contentType.isBlank()) {
            if (allowOctetStream) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片 Content-Type 不能为空");
        }

        String normalizedContentType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (allowOctetStream && MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(normalizedContentType)) {
            // 普通文件上传允许浏览器无法识别类型时使用通用二进制 MIME；图片不放宽。
            return;
        }

        Set<String> allowedTypes = ALLOWED_CONTENT_TYPES.get(extension);
        if (allowedTypes == null || !allowedTypes.contains(normalizedContentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件扩展名与 Content-Type 不匹配");
        }
    }

    private void validateImageSignature(MultipartFile file, String extension) {
        byte[] header;
        try (InputStream inputStream = file.getInputStream()) {
            header = inputStream.readNBytes(12);
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "图片读取失败",
                    exception
            );
        }

        boolean matches = switch (extension) {
            case "jpg", "jpeg" -> startsWith(header, 0xff, 0xd8, 0xff);
            case "png" -> startsWith(header, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case "gif" -> startsWith(header, 0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
                    || startsWith(header, 0x47, 0x49, 0x46, 0x38, 0x39, 0x61);
            case "webp" -> startsWith(header, 0x52, 0x49, 0x46, 0x46)
                    && startsWithAt(header, 8, 0x57, 0x45, 0x42, 0x50);
            case "bmp" -> startsWith(header, 0x42, 0x4d);
            default -> false;
        };

        if (!matches) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片内容与扩展名不匹配");
        }
    }

    private boolean startsWith(byte[] actual, int... expected) {
        return startsWithAt(actual, 0, expected);
    }

    private boolean startsWithAt(byte[] actual, int offset, int... expected) {
        if (actual.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (Byte.toUnsignedInt(actual[offset + index]) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private Path resolveStoredPath(String storedName) {
        // 先限制为系统生成的 UUID 文件名，再校验 normalize 后仍在上传目录内。
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
            // 补偿删除不能覆盖原始业务异常，但必须记录失败供后续排查。
            LOGGER.warn("Unable to delete uploaded file {}", path, exception);
        }
    }

    public record StoredFile(String storedName, String originalFilename) {
    }

    public record AnalyzableFile(Resource resource, MediaType mediaType, long size) {
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {

        private final String filename;

        private NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}

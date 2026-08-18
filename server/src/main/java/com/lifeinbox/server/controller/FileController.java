package com.lifeinbox.server.controller;

import com.lifeinbox.server.service.FileStorageService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * 文件名虽然来自 URL，仍会在存储层重新校验格式和目录边界。
     * nosniff 用于避免浏览器把普通文件误判成可执行的 HTML 等内容。
     */
    @GetMapping("/{storedName}")
    public ResponseEntity<Resource> getFile(@PathVariable String storedName) {
        Resource resource = fileStorageService.loadAsResource(storedName);
        ContentDisposition contentDisposition = ContentDisposition.inline()
                .filename(storedName, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(fileStorageService.mediaTypeFor(storedName))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }
}

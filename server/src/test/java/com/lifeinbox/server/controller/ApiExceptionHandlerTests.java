package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.AiHealthErrorResponse;
import com.lifeinbox.server.exception.FileAnalyzeException;
import com.lifeinbox.server.exception.UrlAnalyzeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiExceptionHandlerTests {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void returnsSafeStructuredUrlError() {
        UrlAnalyzeException exception = UrlAnalyzeException.fromUpstream(
                "URL_FETCH_TIMEOUT",
                408
        ).orElseThrow();

        ResponseEntity<Map<String, String>> response = handler.handleUrlAnalyze(exception);

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
        assertEquals(Map.of(
                "code", "URL_FETCH_TIMEOUT",
                "detail", "网页读取超时"
        ), response.getBody());
    }

    @Test
    void returnsSafeStructuredFileError() {
        FileAnalyzeException exception = FileAnalyzeException.fromUpstream(
                "FILE_PDF_NO_TEXT",
                422
        ).orElseThrow();

        ResponseEntity<Map<String, String>> response = handler.handleFileAnalyze(exception);

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, response.getStatusCode());
        assertEquals(Map.of(
                "code", "FILE_PDF_NO_TEXT",
                "detail", "无法从 PDF 提取有效文本，文件可能需要 OCR"
        ), response.getBody());
    }

    @Test
    void keepsUnknownAiFailureOnExistingGeneric503Contract() {
        ResponseEntity<AiHealthErrorResponse> response = handler.handleAiServiceUnavailable();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(
                new AiHealthErrorResponse("unavailable", "life-inbox-ai", "AI 服务暂不可用"),
                response.getBody()
        );
    }
}

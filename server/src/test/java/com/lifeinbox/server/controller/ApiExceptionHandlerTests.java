package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.AiHealthErrorResponse;
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
    void keepsUnknownAiFailureOnExistingGeneric503Contract() {
        ResponseEntity<AiHealthErrorResponse> response = handler.handleAiServiceUnavailable();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(
                new AiHealthErrorResponse("unavailable", "life-inbox-ai", "AI 服务暂不可用"),
                response.getBody()
        );
    }
}

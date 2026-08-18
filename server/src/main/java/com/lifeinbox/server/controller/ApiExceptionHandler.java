package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.AiHealthErrorResponse;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Python 不可用时只让当前 AI 健康请求返回 503，Spring Boot 和原有 Capture 接口继续运行。
     */
    @ExceptionHandler(AiServiceUnavailableException.class)
    public ResponseEntity<AiHealthErrorResponse> handleAiServiceUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new AiHealthErrorResponse(
                        "unavailable",
                        "life-inbox-ai",
                        "AI 服务暂不可用"
                )
        );
    }

    /**
     * multipart 大小限制由 Spring MVC 在进入 Controller 前检查，
     * 因此需要在全局异常处理器中把框架异常转换成清晰的 API 响应。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUploadSizeExceeded() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(Map.of(
                "title", "上传内容过大",
                "detail", "普通文件最大 20MB，图片最大 10MB"
        ));
    }
}

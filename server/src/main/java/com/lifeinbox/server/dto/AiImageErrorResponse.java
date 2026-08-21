package com.lifeinbox.server.dto;

/** Python IMAGE Analyze 返回的受控 OCR 错误结构。 */
public record AiImageErrorResponse(String code, String detail) {
}

package com.lifeinbox.server.dto;

/** Python URL Analyze 失败时的受控错误结构；detail 不直接暴露给产品调用方。 */
public record AiUrlErrorResponse(String code, String detail) {
}

package com.lifeinbox.server.dto;

/** AI 服务不可用时返回给 API 调用方的安全、结构化错误。 */
public record AiHealthErrorResponse(String status, String service, String message) {
}

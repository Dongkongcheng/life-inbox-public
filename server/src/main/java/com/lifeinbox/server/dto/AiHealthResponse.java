package com.lifeinbox.server.dto;

/**
 * Java 与 Python 健康检查共用的明确响应结构，避免用无约束的 Map 传递跨服务数据。
 */
public record AiHealthResponse(String status, String service) {
}

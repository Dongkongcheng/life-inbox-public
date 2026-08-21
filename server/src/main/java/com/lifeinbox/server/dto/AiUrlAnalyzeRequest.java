package com.lifeinbox.server.dto;

/** Java 只把 URL 业务字段交给 Python，网页正文由 AI Engine 安全提取。 */
public record AiUrlAnalyzeRequest(String title, String url) {
}

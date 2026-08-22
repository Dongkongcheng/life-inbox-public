package com.lifeinbox.server.dto;

/** Java 只请求有界语义候选，不把 MySQL 业务过滤复制到 Python。 */
public record AiSemanticSearchRequest(String query, int limit) {
}

package com.lifeinbox.server.dto;

/** Java 只传稳定业务 ID 与当前 Searchable Content，不直接访问 Qdrant。 */
public record AiVectorIndexRequest(Long inboxItemId, String text) {
}

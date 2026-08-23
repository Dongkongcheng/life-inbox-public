package com.lifeinbox.server.dto;

/** Rerank 只接收稳定业务 ID 与有界相关性文本，不接收完整 InboxItem。 */
public record AiRerankDocument(Long id, String text) {
}

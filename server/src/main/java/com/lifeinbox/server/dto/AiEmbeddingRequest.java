package com.lifeinbox.server.dto;

/** Java 只向 Python 传已准备文本，不传 InboxItem、文件路径或业务状态。 */
public record AiEmbeddingRequest(String text) {
}

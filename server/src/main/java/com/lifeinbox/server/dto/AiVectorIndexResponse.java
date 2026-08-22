package com.lifeinbox.server.dto;

/** Python 完成 Embedding 与 Qdrant Upsert 后返回的最小内部结果。 */
public record AiVectorIndexResponse(
        Long inboxItemId,
        boolean indexed,
        String collection,
        String model,
        Integer dimension,
        String contentHash
) {
}

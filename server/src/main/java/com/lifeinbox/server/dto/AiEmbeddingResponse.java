package com.lifeinbox.server.dto;

import java.util.List;

/** Task 25 的瞬时向量结果；当前不写入 InboxItem 或 MySQL。 */
public record AiEmbeddingResponse(
        String model,
        int dimension,
        List<Double> embedding
) {
}

package com.lifeinbox.server.dto;

/** Rerank Score 是当前 Query 的瞬时元数据，只用于恢复最终顺序。 */
public record AiRerankCandidate(Long id, Double score) {
}

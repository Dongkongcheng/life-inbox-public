package com.lifeinbox.server.dto;

import java.util.List;

/** Python Rerank 返回的最小 ID/Score 集合。 */
public record AiRerankResponse(List<AiRerankCandidate> results) {
}

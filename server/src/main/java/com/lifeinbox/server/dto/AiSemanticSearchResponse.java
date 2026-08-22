package com.lifeinbox.server.dto;

import java.util.List;

/** Python Semantic Retrieval 返回的最小候选集合。 */
public record AiSemanticSearchResponse(List<AiSemanticSearchCandidate> results) {
}

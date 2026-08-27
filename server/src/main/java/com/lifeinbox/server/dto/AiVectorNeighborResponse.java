package com.lifeinbox.server.dto;

import java.util.List;

public record AiVectorNeighborResponse(
        Boolean sourceIndexed,
        List<AiVectorNeighborCandidate> results
) {
}

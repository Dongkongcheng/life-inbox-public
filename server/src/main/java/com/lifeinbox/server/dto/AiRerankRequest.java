package com.lifeinbox.server.dto;

import java.util.List;

/** Java 只把 RRF 已产生的有限候选交给 Python 精排。 */
public record AiRerankRequest(
        String query,
        List<AiRerankDocument> documents,
        int topK
) {
}

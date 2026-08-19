package com.lifeinbox.server.dto;

import java.util.List;

/** Python 一次 Analyze 返回的结构化结果。 */
public record AiAnalyzeResponse(
        String summary,
        String category,
        List<String> tags,
        List<String> keywords,
        List<AiEntityResponse> entities
) {
}

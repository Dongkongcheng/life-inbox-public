package com.lifeinbox.server.dto;

import java.util.List;

/** Task 31 的内部响应；Boolean 使用包装类型以便识别缺失字段。 */
public record AiActionExtractionResponse(
        Boolean hasAction,
        List<AiActionCandidateResponse> actions
) {
}

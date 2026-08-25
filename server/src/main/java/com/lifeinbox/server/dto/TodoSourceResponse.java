package com.lifeinbox.server.dto;

/** Todo 的按需来源上下文；引用失效时返回可用部分，而不是让 Todo 本身不可用。 */
public record TodoSourceResponse(
        Long todoId,
        boolean sourceAvailable,
        InboxSourceSummaryResponse inboxItem,
        ActionCandidateSourceResponse actionCandidate
) {
}

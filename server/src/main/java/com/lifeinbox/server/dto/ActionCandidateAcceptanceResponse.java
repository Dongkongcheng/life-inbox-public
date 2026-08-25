package com.lifeinbox.server.dto;

/** Accept 成功后同时返回已确认 Candidate 和其唯一 Todo。 */
public record ActionCandidateAcceptanceResponse(
        ActionCandidateResponse candidate,
        TodoResponse todo
) {
}

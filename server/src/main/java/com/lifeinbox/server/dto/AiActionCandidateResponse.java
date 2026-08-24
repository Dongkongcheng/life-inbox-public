package com.lifeinbox.server.dto;

/** FastAPI Action Candidate 的内部传输结构，进入业务层后仍需再次校验。 */
public record AiActionCandidateResponse(
        String actionType,
        String title,
        String deadlineText,
        String deadline,
        String evidence
) {
}

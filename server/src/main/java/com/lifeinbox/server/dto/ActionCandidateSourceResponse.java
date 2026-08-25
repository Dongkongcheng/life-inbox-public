package com.lifeinbox.server.dto;

import com.lifeinbox.server.entity.ActionCandidateStatus;
import com.lifeinbox.server.entity.ActionCandidateType;

import java.time.LocalDate;

/** 只读 Candidate 来源说明，同时保留日期原文与可空归一化日期。 */
public record ActionCandidateSourceResponse(
        Long id,
        ActionCandidateType actionType,
        String title,
        String deadlineText,
        LocalDate deadline,
        String evidence,
        ActionCandidateStatus status
) {
}

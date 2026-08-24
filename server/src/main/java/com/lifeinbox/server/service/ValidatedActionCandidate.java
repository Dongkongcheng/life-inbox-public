package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.ActionCandidateType;

import java.time.LocalDate;

/** 已通过 Java 外部服务边界校验、可以进入短持久化事务的 Candidate。 */
record ValidatedActionCandidate(
        ActionCandidateType actionType,
        String title,
        String deadlineText,
        LocalDate deadline,
        String evidence
) {
}

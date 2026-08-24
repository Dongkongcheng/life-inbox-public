package com.lifeinbox.server.dto;

import com.lifeinbox.server.entity.ActionCandidateStatus;
import com.lifeinbox.server.entity.ActionCandidateType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 产品 API 返回结构，不直接暴露 FastAPI 的内部 DTO。 */
public record ActionCandidateResponse(
        Long id,
        Long inboxItemId,
        ActionCandidateType actionType,
        String title,
        String deadlineText,
        LocalDate deadline,
        String evidence,
        ActionCandidateStatus status,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
}

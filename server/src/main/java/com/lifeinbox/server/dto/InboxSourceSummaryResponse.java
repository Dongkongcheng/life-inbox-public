package com.lifeinbox.server.dto;

import java.time.LocalDateTime;

/** Todo 来源中的最小 Inbox 摘要；不暴露完整检索正文或内部处理状态。 */
public record InboxSourceSummaryResponse(
        Long id,
        String type,
        String title,
        String preview,
        String sourceUrl,
        String fileUrl,
        String status,
        LocalDateTime createdTime
) {
}

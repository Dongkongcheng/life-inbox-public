package com.lifeinbox.server.dto;

import java.time.LocalDateTime;

/** Related Items 面板需要的最小 InboxItem 信息，不暴露完整正文或内部处理状态。 */
public record RelatedInboxItemSummaryResponse(
        Long id,
        String type,
        String title,
        String summary,
        String category,
        String preview,
        boolean favorite,
        LocalDateTime createdTime
) {
}

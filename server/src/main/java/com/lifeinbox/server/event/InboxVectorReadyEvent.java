package com.lifeinbox.server.event;

/** 只有 Qdrant 明确返回 indexed=true 后才发布，不代表普通 Capture 或 Analyze 成功。 */
public record InboxVectorReadyEvent(Long inboxItemId) {
}

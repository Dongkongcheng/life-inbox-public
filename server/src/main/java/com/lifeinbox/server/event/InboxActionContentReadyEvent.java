package com.lifeinbox.server.event;

/**
 * 某条 InboxItem 已拥有可直接消费的 Action 正文。
 * 事件只携带主键，后台任务会重新读取当前业务状态与最新正文。
 */
public record InboxActionContentReadyEvent(Long inboxItemId) {
}

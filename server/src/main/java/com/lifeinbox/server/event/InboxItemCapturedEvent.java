package com.lifeinbox.server.event;

/**
 * InboxItem 成功写入事务时发布的领域事件，只携带业务主键，避免把旧实体快照带入后台线程。
 */
public record InboxItemCapturedEvent(Long inboxItemId) {
}

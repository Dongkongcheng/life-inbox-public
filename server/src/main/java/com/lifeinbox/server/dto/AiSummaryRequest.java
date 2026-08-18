package com.lifeinbox.server.dto;

/** 发给 Python 的最小摘要输入，不传递完整 InboxItem 或业务状态。 */
public record AiSummaryRequest(String title, String text) {
}

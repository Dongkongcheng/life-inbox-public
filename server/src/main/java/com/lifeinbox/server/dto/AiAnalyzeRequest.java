package com.lifeinbox.server.dto;

/** 发给 Python 的最小分析输入，不暴露完整 InboxItem 或业务状态。 */
public record AiAnalyzeRequest(String title, String text) {
}

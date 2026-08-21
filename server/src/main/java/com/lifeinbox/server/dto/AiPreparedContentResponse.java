package com.lifeinbox.server.dto;

/** Python Parser/OCR 返回的纯文本，不包含 LLM 结果或 InboxItem 业务状态。 */
public record AiPreparedContentResponse(String title, String text) {
}

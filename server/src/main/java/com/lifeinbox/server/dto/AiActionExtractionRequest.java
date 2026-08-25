package com.lifeinbox.server.dto;

import java.time.LocalDate;

/** Java 把有界纯文本和 Source 的稳定日期上下文交给 Action Extraction。 */
public record AiActionExtractionRequest(String text, LocalDate referenceDate) {
}

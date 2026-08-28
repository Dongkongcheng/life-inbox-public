package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.InboxItem;

import java.util.Locale;

/** 统一构建只读产品摘要，按 Unicode Code Point 截断，避免拆开中文扩展字符或 Emoji。 */
final class InboxItemPreviewBuilder {

    static final int MAX_PREVIEW_CHARS = 300;

    private InboxItemPreviewBuilder() {
    }

    static String build(InboxItem inboxItem) {
        String type = inboxItem.getType() == null
                ? ""
                : inboxItem.getType().strip().toUpperCase(Locale.ROOT);
        String source = "TEXT".equals(type)
                ? firstPresent(inboxItem.getContent(), inboxItem.getTitle())
                : firstPresent(inboxItem.getSearchableContent(), inboxItem.getTitle());
        return truncate(normalizePlainText(source));
    }

    private static String firstPresent(String primary, String fallback) {
        return isBlank(primary) ? fallback : primary;
    }

    private static String normalizePlainText(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String truncate(String value) {
        if (value == null || value.codePointCount(0, value.length()) <= MAX_PREVIEW_CHARS) {
            return value;
        }
        int end = value.offsetByCodePoints(0, MAX_PREVIEW_CHARS - 1);
        return value.substring(0, end) + "…";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

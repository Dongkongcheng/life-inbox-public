package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.InboxItem;
import org.springframework.stereotype.Component;

/** 从既有 Title、Summary 和可检索正文构建有界 Relation 判断文本。 */
@Component
public class RelationDiscoveryTextBuilder {

    private final InboxSearchableContentService searchableContentService;

    public RelationDiscoveryTextBuilder(
            InboxSearchableContentService searchableContentService
    ) {
        this.searchableContentService = searchableContentService;
    }

    public String buildSource(InboxItem source) {
        return buildText(source, RelationDiscoveryLimits.MAX_SOURCE_TEXT_CHARS);
    }

    public String buildCandidate(InboxItem candidate) {
        return buildText(candidate, RelationDiscoveryLimits.MAX_CANDIDATE_TEXT_CHARS);
    }

    String buildText(InboxItem item, int maxChars) {
        if (item == null) {
            return null;
        }

        StringBuilder text = new StringBuilder(maxChars);
        appendSection(text, "标题", searchableContentService.normalize(item.getTitle()), maxChars);
        appendSection(text, "摘要", searchableContentService.normalize(item.getSummary()), maxChars);
        appendSection(
                text,
                "正文",
                searchableContentService.resolveForRetrieval(item),
                maxChars
        );
        return text.isEmpty() ? null : text.toString();
    }

    private void appendSection(
            StringBuilder target,
            String label,
            String value,
            int maxChars
    ) {
        if (value == null || target.length() >= maxChars) {
            return;
        }

        String prefix = (target.isEmpty() ? "" : "\n") + label + "：";
        int remaining = maxChars - target.length();
        if (remaining <= prefix.length()) {
            return;
        }
        String truncatedValue = truncateWithoutSplittingSurrogate(
                value,
                remaining - prefix.length()
        );
        if (!truncatedValue.isEmpty()) {
            target.append(prefix).append(truncatedValue);
        }
    }

    private String truncateWithoutSplittingSurrogate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        int endIndex = maxChars;
        if (endIndex > 0
                && Character.isHighSurrogate(value.charAt(endIndex - 1))
                && Character.isLowSurrogate(value.charAt(endIndex))) {
            endIndex--;
        }
        // 截断只影响本次 LLM 表示，InboxItem 与 Searchable Content 保持不变。
        return value.substring(0, endIndex);
    }
}

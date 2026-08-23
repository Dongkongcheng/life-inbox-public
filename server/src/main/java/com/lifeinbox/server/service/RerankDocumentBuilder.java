package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.AiRerankDocument;
import com.lifeinbox.server.entity.InboxItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 为 Reranker 构建有界的 Item-level 文本表示，不改变 Task 24 Searchable Content。 */
@Component
public class RerankDocumentBuilder {

    public static final int MAX_RERANK_TEXT_CHARS = 2_000;

    private final InboxSearchableContentService searchableContentService;

    public RerankDocumentBuilder(InboxSearchableContentService searchableContentService) {
        this.searchableContentService = searchableContentService;
    }

    public List<AiRerankDocument> build(List<InboxItem> candidates) {
        List<AiRerankDocument> documents = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        for (InboxItem candidate : candidates) {
            if (candidate == null
                    || candidate.getId() == null
                    || candidate.getId() <= 0
                    || !seenIds.add(candidate.getId())) {
                continue;
            }

            String text = buildText(candidate);
            if (text != null) {
                documents.add(new AiRerankDocument(candidate.getId(), text));
            }
        }
        return List.copyOf(documents);
    }

    String buildText(InboxItem candidate) {
        StringBuilder text = new StringBuilder(MAX_RERANK_TEXT_CHARS);
        appendSection(text, "标题", searchableContentService.normalize(candidate.getTitle()));
        appendSection(text, "摘要", searchableContentService.normalize(candidate.getSummary()));
        appendSection(
                text,
                "正文",
                searchableContentService.resolveForRetrieval(candidate)
        );
        return text.isEmpty() ? null : text.toString();
    }

    private void appendSection(StringBuilder target, String label, String value) {
        if (value == null || target.length() >= MAX_RERANK_TEXT_CHARS) {
            return;
        }

        String prefix = (target.isEmpty() ? "" : "\n") + label + "：";
        int remaining = MAX_RERANK_TEXT_CHARS - target.length();
        if (remaining <= prefix.length()) {
            return;
        }
        String truncatedValue = truncateWithoutSplittingSurrogate(
                value,
                remaining - prefix.length()
        );
        if (truncatedValue.isEmpty()) {
            return;
        }
        target.append(prefix);
        target.append(truncatedValue);
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
        // 截断集中在 Rerank 表示层，原始正文和 Searchable Content 均保持不变。
        return value.substring(0, endIndex);
    }
}

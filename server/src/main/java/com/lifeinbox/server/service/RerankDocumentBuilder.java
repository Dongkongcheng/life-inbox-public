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
        return searchableContentService.buildBoundedRetrievalText(
                candidate,
                MAX_RERANK_TEXT_CHARS
        );
    }
}

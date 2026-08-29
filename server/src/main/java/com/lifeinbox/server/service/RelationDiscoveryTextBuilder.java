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
        return searchableContentService.buildBoundedRetrievalText(item, maxChars);
    }
}

package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RelationDiscoveryTextBuilderTests {

    private final RelationDiscoveryTextBuilder builder = new RelationDiscoveryTextBuilder(
            new InboxSearchableContentService(mock(InboxItemMapper.class))
    );

    @Test
    void buildsTextFromTitleSummaryAndUsableContentOnly() {
        InboxItem source = item(123L, "TEXT", "事务失效", "检查代理调用");
        source.setSummary("Spring 事务排查记录");
        source.setSearchableContent("TEXT 不应读取的派生正文");
        source.setSourceUrl("https://secret.example/source");

        String text = builder.buildSource(source);

        assertEquals("标题：事务失效\n摘要：Spring 事务排查记录\n正文：检查代理调用", text);
        assertFalse(text.contains("派生正文"));
        assertFalse(text.contains("secret"));
    }

    @Test
    void nonTextUsesExistingSearchableContentWithoutRefetching() {
        InboxItem candidate = item(456L, "URL", "网页标题", "不应使用的 content");
        candidate.setSearchableContent("已经提取的网页正文");

        assertEquals(
                "标题：网页标题\n正文：已经提取的网页正文",
                builder.buildCandidate(candidate)
        );
    }

    @Test
    void sourceAndCandidateUseCentralSurrogateSafeBounds() {
        InboxItem source = item(123L, "TEXT", null, "源".repeat(5_000) + "🙂");
        InboxItem candidate = item(456L, "TEXT", null, "候".repeat(2_000) + "🙂");

        String sourceText = builder.buildSource(source);
        String candidateText = builder.buildCandidate(candidate);

        assertEquals(RelationDiscoveryLimits.MAX_SOURCE_TEXT_CHARS, sourceText.length());
        assertEquals(RelationDiscoveryLimits.MAX_CANDIDATE_TEXT_CHARS, candidateText.length());
        assertFalse(Character.isHighSurrogate(sourceText.charAt(sourceText.length() - 1)));
        assertFalse(Character.isHighSurrogate(candidateText.charAt(candidateText.length() - 1)));
        assertEquals(24_000, RelationDiscoveryLimits.MAX_TOTAL_TEXT_CHARS);
        assertTrue(source.getContent().endsWith("🙂"));
        assertTrue(candidate.getContent().endsWith("🙂"));
    }

    @Test
    void noTitleSummaryOrUsableBodyProducesNoLlmText() {
        InboxItem item = item(456L, "URL", null, "URL content 不可用");

        assertNull(builder.buildCandidate(item));
    }

    private InboxItem item(Long id, String type, String title, String content) {
        InboxItem item = new InboxItem();
        item.setId(id);
        item.setType(type);
        item.setTitle(title);
        item.setContent(content);
        item.setStatus("ACTIVE");
        return item;
    }
}

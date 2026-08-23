package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.AiRerankDocument;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RerankDocumentBuilderTests {

    private final RerankDocumentBuilder builder = new RerankDocumentBuilder(
            new InboxSearchableContentService(mock(InboxItemMapper.class))
    );

    @Test
    void buildsTextCandidateFromTitleSummaryAndTextContentOnly() {
        InboxItem item = item(1L, "TEXT", "接口幂等", "使用 Redisson 防止重复提交");
        item.setSummary("同一请求只执行一次");
        item.setCategory("不应进入文本的分类");
        item.setStatus("不应进入文本的状态");
        item.setSourceUrl("https://secret.example/source");
        item.setFileUrl("/api/files/secret.txt");
        item.setFavorite(1);
        item.setCreatedTime(LocalDateTime.of(2026, 8, 21, 10, 0));

        AiRerankDocument document = builder.build(List.of(item)).getFirst();

        assertEquals(1L, document.id());
        assertEquals(
                "标题：接口幂等\n摘要：同一请求只执行一次\n正文：使用 Redisson 防止重复提交",
                document.text()
        );
        assertFalse(document.text().contains("分类"));
        assertFalse(document.text().contains("状态"));
        assertFalse(document.text().contains("secret"));
        assertFalse(document.text().contains("2026"));
    }

    @Test
    void nonTextCandidateUsesPreparedSearchableContentInsteadOfContent() {
        InboxItem item = item(2L, "URL", "网页标题", "错误的 TEXT content");
        item.setSearchableContent("提取后的网页正文");

        AiRerankDocument document = builder.build(List.of(item)).getFirst();

        assertEquals("标题：网页标题\n正文：提取后的网页正文", document.text());
        assertFalse(document.text().contains("错误的 TEXT content"));
    }

    @Test
    void truncatesOnlyRerankRepresentationAtCentralLimit() {
        String content = "正文".repeat(2_000) + "🙂";
        InboxItem item = item(3L, "TEXT", "标题", content);

        String text = builder.build(List.of(item)).getFirst().text();

        assertEquals(RerankDocumentBuilder.MAX_RERANK_TEXT_CHARS, text.length());
        assertTrue(text.startsWith("标题：标题\n正文："));
        assertEquals(content, item.getContent());
        assertFalse(Character.isHighSurrogate(text.charAt(text.length() - 1)));
    }

    @Test
    void skipsCandidateWithoutAnyRelevanceTextAndDeduplicatesIds() {
        InboxItem empty = item(4L, "URL", null, null);
        InboxItem first = item(5L, "TEXT", "保留", null);
        InboxItem duplicate = item(5L, "TEXT", "重复", null);

        List<AiRerankDocument> result = builder.build(List.of(empty, first, duplicate));

        assertEquals(List.of(5L), result.stream().map(AiRerankDocument::id).toList());
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

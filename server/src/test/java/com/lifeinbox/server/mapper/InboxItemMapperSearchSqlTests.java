package com.lifeinbox.server.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboxItemMapperSearchSqlTests {

    private final String searchSql = normalizedSearchSql();

    @Test
    void searchKeepsExistingFieldsAndAddsAllAiDerivedMatchPaths() {
        assertAll(
                () -> assertTrue(searchSql.contains("i.title LIKE")),
                () -> assertTrue(searchSql.contains("i.content LIKE")),
                () -> assertTrue(searchSql.contains("i.summary LIKE")),
                () -> assertTrue(searchSql.contains("i.category LIKE")),
                () -> assertTrue(searchSql.contains("FROM inbox_tag it INNER JOIN tag t")),
                () -> assertTrue(searchSql.contains("AND t.name LIKE")),
                () -> assertTrue(searchSql.contains("FROM inbox_keyword ik")),
                () -> assertTrue(searchSql.contains("AND ik.keyword LIKE")),
                () -> assertTrue(searchSql.contains("FROM inbox_entity ie")),
                () -> assertTrue(searchSql.contains("AND ie.name LIKE"))
        );
    }

    @Test
    void activeFilterWrapsEveryOrPathIncludingAiRelations() {
        assertTrue(searchSql.contains("WHERE i.status = 'ACTIVE' AND ( i.title LIKE"));
        assertTrue(searchSql.indexOf("WHERE i.status = 'ACTIVE'") < searchSql.indexOf("OR EXISTS"));
    }

    @Test
    void existsSubqueriesPreventMultipleMetadataRowsFromDuplicatingInboxItem() {
        assertTrue(searchSql.startsWith("SELECT i.* FROM inbox_item i"));
        assertEquals(3, occurrences(searchSql, "OR EXISTS ("));
        assertEquals(3, occurrences(searchSql, "WHERE it.inbox_item_id = i.id")
                + occurrences(searchSql, "WHERE ik.inbox_item_id = i.id")
                + occurrences(searchSql, "WHERE ie.inbox_item_id = i.id"));
    }

    @Test
    void allFieldsReuseParameterizedLiteralLikePolicyAndTask21Ordering() {
        assertEquals(7, occurrences(searchSql, "#{escapedQuery}"));
        assertEquals(7, occurrences(searchSql, "ESCAPE '!'"));
        assertTrue(searchSql.endsWith("ORDER BY i.created_time DESC, i.id DESC"));
    }

    private String normalizedSearchSql() {
        try {
            Method method = InboxItemMapper.class.getMethod("searchActiveByKeyword", String.class);
            Select select = method.getAnnotation(Select.class);
            return String.join(" ", select.value()).replaceAll("\\s+", " ").trim();
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("Search Mapper 方法不存在", exception);
        }
    }

    private int occurrences(String value, String fragment) {
        return (value.length() - value.replace(fragment, "").length()) / fragment.length();
    }
}

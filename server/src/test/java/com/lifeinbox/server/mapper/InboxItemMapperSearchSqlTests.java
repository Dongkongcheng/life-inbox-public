package com.lifeinbox.server.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboxItemMapperSearchSqlTests {

    private final String searchSql = normalizedSearchSql();
    private final String semanticResolutionSql = normalizedSemanticResolutionSql();

    @Test
    void searchKeepsExistingFieldsAndAddsAllAiDerivedMatchPaths() {
        assertAll(
                () -> assertTrue(searchSql.contains("i.title LIKE")),
                () -> assertTrue(searchSql.contains("i.content LIKE")),
                () -> assertTrue(searchSql.contains("i.summary LIKE")),
                () -> assertTrue(searchSql.contains("i.searchable_content LIKE")),
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
        assertTrue(searchSql.contains("WHERE i.status = 'ACTIVE'"));
        assertTrue(searchSql.contains("AND (#{favorite,jdbcType=TINYINT} IS NULL"
                + " OR i.favorite = #{favorite,jdbcType=TINYINT}) AND ( i.title LIKE"));
        assertTrue(searchSql.indexOf("WHERE i.status = 'ACTIVE'") < searchSql.indexOf("OR EXISTS"));
    }

    @Test
    void optionalFiltersUseExistingColumnsAndRemainParameterized() {
        assertAll(
                () -> assertTrue(searchSql.contains("AND (#{type,jdbcType=VARCHAR} IS NULL"
                        + " OR i.type = #{type,jdbcType=VARCHAR})")),
                () -> assertTrue(searchSql.contains("AND (#{category,jdbcType=VARCHAR} IS NULL"
                        + " OR i.category = #{category,jdbcType=VARCHAR})")),
                () -> assertTrue(searchSql.contains("AND (#{favorite,jdbcType=TINYINT} IS NULL"
                        + " OR i.favorite = #{favorite,jdbcType=TINYINT})"))
        );
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
        assertEquals(16, occurrences(searchSql, "#{escapedQuery}"));
        assertEquals(16, occurrences(searchSql, "ESCAPE '!'"));
        assertEquals(1, occurrences(searchSql, "#{query}"));
        assertTrue(searchSql.endsWith("END DESC, i.created_time DESC, i.id DESC"));
    }

    @Test
    void rankingUsesDocumentedPriorityAndCreatedTimeTieBreak() {
        int exactTitle = searchSql.indexOf("WHEN i.title = #{query} THEN 9");
        int title = searchSql.indexOf("WHEN i.title LIKE", exactTitle);
        int keyword = searchSql.indexOf("FROM inbox_keyword ik_rank");
        int tag = searchSql.indexOf("FROM inbox_tag it_rank");
        int entity = searchSql.indexOf("FROM inbox_entity ie_rank");
        int summary = searchSql.indexOf("WHEN i.summary LIKE", exactTitle);
        int searchableContent = searchSql.indexOf(
                "WHEN i.searchable_content LIKE",
                exactTitle
        );
        int content = searchSql.indexOf("WHEN i.content LIKE", exactTitle);
        int category = searchSql.indexOf("WHEN i.category LIKE", exactTitle);

        assertAll(
                () -> assertTrue(exactTitle >= 0),
                () -> assertTrue(exactTitle < title),
                () -> assertTrue(title < keyword),
                () -> assertTrue(keyword < tag),
                () -> assertTrue(tag < entity),
                () -> assertTrue(entity < summary),
                () -> assertTrue(summary < searchableContent),
                () -> assertTrue(searchableContent < content),
                () -> assertTrue(content < category),
                () -> assertTrue(searchSql.endsWith("END DESC, i.created_time DESC, i.id DESC"))
        );
    }

    @Test
    void semanticCandidatesUseOneParameterizedActiveBatchQueryWithBusinessFilters() {
        assertAll(
                () -> assertTrue(semanticResolutionSql.startsWith(
                        "<script> SELECT i.* FROM inbox_item i WHERE i.status = 'ACTIVE'"
                )),
                () -> assertTrue(semanticResolutionSql.contains(
                        "AND (#{type,jdbcType=VARCHAR} IS NULL"
                                + " OR i.type = #{type,jdbcType=VARCHAR})"
                )),
                () -> assertTrue(semanticResolutionSql.contains(
                        "AND (#{category,jdbcType=VARCHAR} IS NULL"
                                + " OR i.category = #{category,jdbcType=VARCHAR})"
                )),
                () -> assertTrue(semanticResolutionSql.contains(
                        "AND (#{favorite,jdbcType=TINYINT} IS NULL"
                                + " OR i.favorite = #{favorite,jdbcType=TINYINT})"
                )),
                () -> assertTrue(semanticResolutionSql.contains("AND i.id IN")),
                () -> assertTrue(semanticResolutionSql.contains(
                        "<foreach collection=\"ids\" item=\"id\""
                )),
                () -> assertTrue(semanticResolutionSql.contains("#{id}"))
        );
    }

    private String normalizedSearchSql() {
        try {
            Method method = InboxItemMapper.class.getMethod(
                    "searchActiveByKeyword",
                    String.class,
                    String.class,
                    String.class,
                    String.class,
                    Integer.class
            );
            Select select = method.getAnnotation(Select.class);
            return String.join(" ", select.value()).replaceAll("\\s+", " ").trim();
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("Search Mapper 方法不存在", exception);
        }
    }

    private String normalizedSemanticResolutionSql() {
        try {
            Method method = InboxItemMapper.class.getMethod(
                    "selectActiveByIdsAndFilters",
                    java.util.List.class,
                    String.class,
                    String.class,
                    Integer.class
            );
            Select select = method.getAnnotation(Select.class);
            return String.join(" ", select.value()).replaceAll("\\s+", " ").trim();
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("Semantic Candidate 批量解析 Mapper 方法不存在", exception);
        }
    }

    private int occurrences(String value, String fragment) {
        return (value.length() - value.replace(fragment, "").length()) / fragment.length();
    }
}

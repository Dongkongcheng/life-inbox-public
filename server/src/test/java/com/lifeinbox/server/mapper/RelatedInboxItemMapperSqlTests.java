package com.lifeinbox.server.mapper;

import com.lifeinbox.server.entity.RelationType;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelatedInboxItemMapperSqlTests {

    @Test
    void queryCoversBothSymmetricDirectionsAndOnlyRelatedTo() throws Exception {
        String sql = relatedItemsSql();

        assertTrue(sql.contains("left_inbox_item_id = #{sourceInboxItemId}"));
        assertTrue(sql.contains("right_inbox_item_id = #{sourceInboxItemId}"));
        assertTrue(sql.contains("right_inbox_item_id AS related_inbox_item_id"));
        assertTrue(sql.contains("left_inbox_item_id AS related_inbox_item_id"));
        assertTrue(sql.contains("relation_type = #{relationType}"));
        assertTrue(sql.contains("UNION ALL"));
    }

    @Test
    void queryFiltersActiveTargetsAndIsBoundedAtDatabaseLevel() throws Exception {
        String sql = relatedItemsSql();

        assertTrue(sql.contains("INNER JOIN inbox_item i"));
        assertTrue(sql.contains("i.status = 'ACTIVE'"));
        assertTrue(sql.contains("LIMIT #{limit}"));
    }

    @Test
    void queryUsesDeterministicRelationRecencyOrderingAndIsReadOnly() throws Exception {
        String sql = relatedItemsSql();

        assertTrue(sql.contains("relation_created_time DESC"));
        assertTrue(sql.contains("relation_id DESC"));
        assertTrue(sql.contains("i.id DESC"));
        assertFalse(sql.contains(" INSERT "));
        assertFalse(sql.contains(" UPDATE "));
        assertFalse(sql.contains(" DELETE "));
    }

    private String relatedItemsSql() throws Exception {
        Method method = InboxItemMapper.class.getMethod(
                "selectRelatedActiveItems",
                Long.class,
                RelationType.class,
                int.class
        );
        return String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .trim();
    }
}

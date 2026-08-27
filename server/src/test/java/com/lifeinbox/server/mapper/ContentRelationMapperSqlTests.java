package com.lifeinbox.server.mapper;

import com.lifeinbox.server.entity.ContentRelation;
import com.lifeinbox.server.entity.RelationType;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentRelationMapperSqlTests {

    @Test
    void canonicalPairLookupUsesBothEndpointsAndControlledType() throws Exception {
        Method method = ContentRelationMapper.class.getMethod(
                "selectCanonicalPair",
                Long.class,
                Long.class,
                RelationType.class
        );
        String sql = normalize(method.getAnnotation(Select.class).value());

        assertTrue(sql.contains("left_inbox_item_id = #{leftInboxItemId}"));
        assertTrue(sql.contains("right_inbox_item_id = #{rightInboxItemId}"));
        assertTrue(sql.contains("relation_type = #{relationType}"));
    }

    @Test
    void relationQueryFindsInboxItemOnEitherCanonicalSide() throws Exception {
        Method method = ContentRelationMapper.class.getMethod(
                "selectByInboxItemId",
                Long.class
        );
        String sql = normalize(method.getAnnotation(Select.class).value());

        assertTrue(sql.contains("left_inbox_item_id = #{inboxItemId} OR "
                + "right_inbox_item_id = #{inboxItemId}"));
        assertTrue(sql.contains("ORDER BY created_time ASC, id ASC"));
    }

    @Test
    void endpointValidationLocksRowsInCanonicalOrder() throws Exception {
        Method method = InboxItemMapper.class.getMethod(
                "selectRelationEndpointsForUpdate",
                Long.class,
                Long.class
        );
        String sql = normalize(method.getAnnotation(Select.class).value());

        assertTrue(sql.contains("id IN (#{leftInboxItemId}, #{rightInboxItemId})"));
        assertTrue(sql.contains("ORDER BY id ASC"));
        assertTrue(sql.contains("FOR UPDATE"));
    }

    @Test
    void firstVersionExposesOnlyRelatedToAsAnEnum() throws Exception {
        assertEquals(List.of(RelationType.RELATED_TO), Arrays.asList(RelationType.values()));
        assertEquals(
                RelationType.class,
                ContentRelation.class.getDeclaredField("relationType").getType()
        );
        assertThrows(IllegalArgumentException.class, () -> RelationType.valueOf("SAME_TOPIC"));
    }

    private String normalize(String[] lines) {
        return String.join(" ", lines).replaceAll("\\s+", " ").trim();
    }
}

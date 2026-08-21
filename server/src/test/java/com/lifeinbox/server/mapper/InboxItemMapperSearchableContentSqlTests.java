package com.lifeinbox.server.mapper;

import com.lifeinbox.server.entity.AiProcessingStatus;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboxItemMapperSearchableContentSqlTests {

    @Test
    void searchableContentUpdateRequiresCurrentProcessingAttempt() throws Exception {
        Method method = InboxItemMapper.class.getMethod(
                "updateSearchableContent",
                Long.class,
                String.class,
                AiProcessingStatus.class,
                String.class
        );
        Update update = method.getAnnotation(Update.class);
        String sql = String.join(" ", update.value()).replaceAll("\\s+", " ").trim();

        assertAll(
                () -> assertTrue(sql.startsWith(
                        "UPDATE inbox_item SET searchable_content = #{searchableContent}"
                )),
                () -> assertTrue(sql.contains("WHERE id = #{id}")),
                () -> assertTrue(sql.contains("AND ai_status = #{processingStatus}")),
                () -> assertTrue(sql.endsWith("AND ai_attempt_id = #{attemptId}"))
        );
    }
}

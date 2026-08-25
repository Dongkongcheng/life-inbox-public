package com.lifeinbox.server.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoMapperSqlTests {

    @Test
    void openListIsSourceIndependentAndDeterministicallySorted() throws Exception {
        String sql = selectSql("selectOpenTodos");

        assertTrue(sql.contains("WHERE status = 'OPEN'"));
        assertTrue(sql.contains("CASE WHEN due_date IS NULL THEN 1 ELSE 0 END ASC"));
        assertTrue(sql.contains("due_date ASC"));
        assertTrue(sql.contains("created_time DESC"));
        assertTrue(sql.contains("id DESC"));
        assertFalse(sql.toUpperCase().contains(" JOIN "));
    }

    @Test
    void completedListUsesRecentCompletionAndStableFallback() throws Exception {
        String sql = selectSql("selectCompletedTodos");

        assertTrue(sql.contains("WHERE status = 'COMPLETED'"));
        assertTrue(sql.contains("completed_time IS NULL"));
        assertTrue(sql.contains("completed_time DESC"));
        assertTrue(sql.contains("created_time DESC"));
        assertTrue(sql.contains("id DESC"));
        assertFalse(sql.toUpperCase().contains(" JOIN "));
    }

    @Test
    void lifecycleLocksRowAndUsesExplicitStateConditions() throws Exception {
        String lockSql = selectSql("selectByIdForUpdate", Long.class);
        assertTrue(lockSql.contains("WHERE id = #{todoId}"));
        assertTrue(lockSql.contains("FOR UPDATE"));

        Method complete = TodoMapper.class.getMethod(
                "markOpenCompleted",
                Long.class,
                LocalDateTime.class
        );
        String completeSql = normalize(complete.getAnnotation(Update.class).value());
        assertTrue(completeSql.contains("status = 'COMPLETED'"));
        assertTrue(completeSql.contains("completed_time = #{completedTime}"));
        assertTrue(completeSql.contains("status = 'OPEN'"));

        Method reopen = TodoMapper.class.getMethod("markReopened", Long.class);
        String reopenSql = normalize(reopen.getAnnotation(Update.class).value());
        assertTrue(reopenSql.contains("status = 'OPEN'"));
        assertTrue(reopenSql.contains("completed_time = NULL"));
        assertTrue(reopenSql.contains("status = 'COMPLETED'"));
    }

    private String selectSql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = TodoMapper.class.getMethod(methodName, parameterTypes);
        return normalize(method.getAnnotation(Select.class).value());
    }

    private String normalize(String[] lines) {
        return String.join(" ", lines).replaceAll("\\s+", " ").trim();
    }
}

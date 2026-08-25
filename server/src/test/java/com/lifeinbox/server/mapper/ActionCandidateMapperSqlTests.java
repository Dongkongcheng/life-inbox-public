package com.lifeinbox.server.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionCandidateMapperSqlTests {

    @Test
    void replacementDeleteIsPermanentlyScopedToPending() throws NoSuchMethodException {
        Method method = ActionCandidateMapper.class.getMethod(
                "deletePendingByInboxItemId",
                Long.class
        );
        String sql = method.getAnnotation(Delete.class).value()[0];

        assertTrue(sql.contains("inbox_item_id = #{inboxItemId}"));
        assertTrue(sql.contains("status = 'PENDING'"));
        assertFalse(sql.contains("ACCEPTED"));
        assertFalse(sql.contains("DISMISSED"));
    }

    @Test
    void activeSourceLockUsesDatabaseRowLock() throws NoSuchMethodException {
        Method method = InboxItemMapper.class.getMethod("selectActiveIdForUpdate", Long.class);
        String sql = method.getAnnotation(Select.class).value()[0];

        assertTrue(sql.contains("status = 'ACTIVE'"));
        assertTrue(sql.contains("FOR UPDATE"));
    }

    @Test
    void candidateDecisionUsesRowLockAndPendingOnlyTransitions() throws NoSuchMethodException {
        Method lock = ActionCandidateMapper.class.getMethod(
                "selectByInboxItemIdAndIdForUpdate",
                Long.class,
                Long.class
        );
        String lockSql = lock.getAnnotation(Select.class).value()[0];
        assertTrue(lockSql.contains("inbox_item_id = #{inboxItemId}"));
        assertTrue(lockSql.contains("id = #{candidateId}"));
        assertTrue(lockSql.contains("FOR UPDATE"));

        Method accept = ActionCandidateMapper.class.getMethod("markPendingAccepted", Long.class);
        String acceptSql = accept.getAnnotation(Update.class).value()[0];
        assertTrue(acceptSql.contains("status = 'ACCEPTED'"));
        assertTrue(acceptSql.contains("status = 'PENDING'"));

        Method dismiss = ActionCandidateMapper.class.getMethod("markPendingDismissed", Long.class);
        String dismissSql = dismiss.getAnnotation(Update.class).value()[0];
        assertTrue(dismissSql.contains("status = 'DISMISSED'"));
        assertTrue(dismissSql.contains("status = 'PENDING'"));
    }
}

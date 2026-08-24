package com.lifeinbox.server.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

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
    }

    @Test
    void activeSourceLockUsesDatabaseRowLock() throws NoSuchMethodException {
        Method method = InboxItemMapper.class.getMethod("selectActiveIdForUpdate", Long.class);
        String sql = method.getAnnotation(Select.class).value()[0];

        assertTrue(sql.contains("status = 'ACTIVE'"));
        assertTrue(sql.contains("FOR UPDATE"));
    }
}

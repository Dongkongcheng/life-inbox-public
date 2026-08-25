package com.lifeinbox.server.mapper;

import com.lifeinbox.server.entity.ActionProcessingStatus;
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

    @Test
    void actionAttemptSqlUsesIndependentColumnsAndOwnershipGuards() throws NoSuchMethodException {
        Method claim = InboxItemMapper.class.getMethod(
                "markActionProcessing",
                Long.class,
                ActionProcessingStatus.class,
                String.class,
                java.time.LocalDateTime.class,
                java.time.LocalDateTime.class
        );
        String claimSql = claim.getAnnotation(Update.class).value()[0];
        assertTrue(claimSql.contains("action_status"));
        assertTrue(claimSql.contains("action_attempt_id"));
        assertTrue(claimSql.contains("action_started_time <= #{staleBefore}"));
        assertFalse(claimSql.contains("ai_status"));

        Method owner = InboxItemMapper.class.getMethod(
                "selectCurrentActionAttemptForUpdate",
                Long.class,
                String.class,
                ActionProcessingStatus.class
        );
        String ownerSql = owner.getAnnotation(Select.class).value()[0];
        assertTrue(ownerSql.contains("action_status = #{processingStatus}"));
        assertTrue(ownerSql.contains("action_attempt_id = #{attemptId}"));
        assertTrue(ownerSql.contains("FOR UPDATE"));

        Method failure = InboxItemMapper.class.getMethod(
                "markActionFailed",
                Long.class,
                String.class,
                ActionProcessingStatus.class,
                ActionProcessingStatus.class,
                String.class
        );
        String failureSql = failure.getAnnotation(Update.class).value()[0];
        assertTrue(failureSql.contains("action_attempt_id = #{attemptId}"));
        assertTrue(failureSql.contains("action_status = #{processingStatus}"));
        assertTrue(failureSql.contains("action_finished_time = CURRENT_TIMESTAMP"));
        assertFalse(failureSql.contains("ai_status"));
        assertFalse(failureSql.contains("searchable_content"));

        Method success = InboxItemMapper.class.getMethod(
                "markActionSuccess",
                Long.class,
                String.class,
                ActionProcessingStatus.class,
                ActionProcessingStatus.class
        );
        String successSql = success.getAnnotation(Update.class).value()[0];
        assertTrue(successSql.contains("action_status = #{successStatus}"));
        assertTrue(successSql.contains("action_error_message = NULL"));
        assertTrue(successSql.contains("action_finished_time = CURRENT_TIMESTAMP"));
        assertTrue(successSql.contains("action_attempt_id = #{attemptId}"));
    }

    @Test
    void replacementLocksCandidatesBeforeTerminalDuplicateSuppression() throws NoSuchMethodException {
        Method method = ActionCandidateMapper.class.getMethod(
                "selectByInboxItemIdForUpdate",
                Long.class
        );
        String sql = method.getAnnotation(Select.class).value()[0];

        assertTrue(sql.contains("inbox_item_id = #{inboxItemId}"));
        assertTrue(sql.contains("FOR UPDATE"));
    }
}

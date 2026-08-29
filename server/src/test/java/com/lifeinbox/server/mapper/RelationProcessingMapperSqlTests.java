package com.lifeinbox.server.mapper;

import com.lifeinbox.server.entity.RelationProcessingStatus;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationProcessingMapperSqlTests {

    @Test
    void automaticAndManualClaimsHaveDifferentAllowedStates() throws Exception {
        Method automatic = InboxItemMapper.class.getMethod(
                "markRelationAutomaticProcessing",
                Long.class,
                RelationProcessingStatus.class,
                RelationProcessingStatus.class,
                String.class,
                LocalDateTime.class
        );
        Method manual = InboxItemMapper.class.getMethod(
                "markRelationManualProcessing",
                Long.class,
                RelationProcessingStatus.class,
                RelationProcessingStatus.class,
                RelationProcessingStatus.class,
                String.class,
                LocalDateTime.class,
                LocalDateTime.class
        );
        String automaticSql = normalize(automatic.getAnnotation(Update.class).value());
        String manualSql = normalize(manual.getAnnotation(Update.class).value());

        assertTrue(automaticSql.contains("relation_status = #{notProcessedStatus}"));
        assertTrue(manualSql.contains(
                "relation_status IN (#{notProcessedStatus}, #{failedStatus})"
        ));
        assertTrue(manualSql.contains("relation_started_time <= #{staleBefore}"));
    }

    @Test
    void finalLockLoadsAttemptOwnershipAndSuccessUsesAttemptGuard() throws Exception {
        Method lock = InboxItemMapper.class.getMethod(
                "selectRelationEndpointsForUpdateByIds",
                List.class
        );
        String lockSql = normalize(lock.getAnnotation(Select.class).value());
        Method success = InboxItemMapper.class.getMethod(
                "markRelationSuccess",
                Long.class,
                String.class,
                RelationProcessingStatus.class,
                RelationProcessingStatus.class
        );
        String successSql = normalize(success.getAnnotation(Update.class).value());

        assertTrue(lockSql.contains("relation_status, relation_attempt_id"));
        assertTrue(lockSql.contains("ORDER BY id ASC FOR UPDATE"));
        assertTrue(successSql.contains("relation_status = #{processingStatus}"));
        assertTrue(successSql.contains("relation_attempt_id = #{attemptId}"));
    }

    @Test
    void rediscoveryClaimAndHistoricalSelectionStayStateBounded() throws Exception {
        Method rediscovery = InboxItemMapper.class.getMethod(
                "markRelationRediscoveryProcessing",
                Long.class,
                RelationProcessingStatus.class,
                RelationProcessingStatus.class,
                String.class,
                LocalDateTime.class
        );
        String rediscoverySql = normalize(rediscovery.getAnnotation(Update.class).value());
        Method backfill = InboxItemMapper.class.getMethod(
                "selectRelationBackfillCandidates",
                RelationProcessingStatus.class,
                int.class
        );
        String backfillSql = normalize(backfill.getAnnotation(Select.class).value());

        assertTrue(rediscoverySql.contains("relation_status = #{successStatus}"));
        assertTrue(rediscoverySql.contains("status = 'ACTIVE'"));
        assertTrue(backfillSql.contains("status = 'ACTIVE'"));
        assertTrue(backfillSql.contains("relation_status = #{notProcessedStatus}"));
        assertTrue(backfillSql.contains("ORDER BY id ASC"));
        assertTrue(backfillSql.contains("LIMIT #{scanLimit}"));
    }

    private String normalize(String[] lines) {
        return String.join(" ", lines).replaceAll("\\s+", " ").trim();
    }
}

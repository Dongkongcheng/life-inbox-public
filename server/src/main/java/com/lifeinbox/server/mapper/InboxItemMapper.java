package com.lifeinbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lifeinbox.server.entity.AiProcessingStatus;
import com.lifeinbox.server.entity.InboxItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InboxItemMapper extends BaseMapper<InboxItem> {

    /**
     * 只更新本次 AI 分析拥有的列；同时取得该 InboxItem 的行锁，串行化并发重分析。
     */
    @Update("""
            UPDATE inbox_item
            SET summary = #{summary}, category = #{category}
            WHERE id = #{id}
              AND ai_status = #{processingStatus}
              AND ai_attempt_id = #{attemptId}
            """)
    int updateAnalysis(
            @Param("id") Long id,
            @Param("attemptId") String attemptId,
            @Param("processingStatus") AiProcessingStatus processingStatus,
            @Param("summary") String summary,
            @Param("category") String category
    );

    /**
     * 单条条件 UPDATE 同时完成领取和状态修改。非 PROCESSING 可重试，
     * 已超过阈值的 PROCESSING 也可由新 Attempt 接管。
     */
    @Update("""
            UPDATE inbox_item
            SET ai_status = #{processingStatus},
                ai_attempt_id = #{attemptId},
                ai_error_message = NULL,
                ai_started_time = #{startedTime},
                ai_finished_time = NULL
            WHERE id = #{id}
              AND (
                    ai_status <> #{processingStatus}
                    OR ai_started_time IS NULL
                    OR ai_started_time <= #{staleBefore}
              )
            """)
    int markAnalysisProcessing(
            @Param("id") Long id,
            @Param("processingStatus") AiProcessingStatus processingStatus,
            @Param("attemptId") String attemptId,
            @Param("startedTime") java.time.LocalDateTime startedTime,
            @Param("staleBefore") java.time.LocalDateTime staleBefore
    );

    /** SUCCESS 与五类结果在同一事务提交，并用 Attempt Guard 拒绝迟到的旧成功。 */
    @Update("""
            UPDATE inbox_item
            SET ai_status = #{successStatus},
                ai_error_message = NULL,
                ai_finished_time = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND ai_status = #{processingStatus}
              AND ai_attempt_id = #{attemptId}
            """)
    int markAnalysisSuccess(
            @Param("id") Long id,
            @Param("attemptId") String attemptId,
            @Param("processingStatus") AiProcessingStatus processingStatus,
            @Param("successStatus") AiProcessingStatus successStatus
    );

    /** FAILED 也要匹配 Attempt，避免迟到的旧失败把新请求从 PROCESSING 改成 FAILED。 */
    @Update("""
            UPDATE inbox_item
            SET ai_status = #{failedStatus},
                ai_error_message = #{errorMessage},
                ai_finished_time = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND ai_status = #{processingStatus}
              AND ai_attempt_id = #{attemptId}
            """)
    int markAnalysisFailed(
            @Param("id") Long id,
            @Param("attemptId") String attemptId,
            @Param("processingStatus") AiProcessingStatus processingStatus,
            @Param("failedStatus") AiProcessingStatus failedStatus,
            @Param("errorMessage") String errorMessage
    );

    /** 普通 Inbox 操作只更新自己的列，避免用旧实体覆盖并发中的 AI 状态。 */
    @Update("UPDATE inbox_item SET status = #{status} WHERE id = #{id}")
    int updateInboxStatus(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE inbox_item SET favorite = #{favorite} WHERE id = #{id}")
    int updateFavorite(@Param("id") Long id, @Param("favorite") int favorite);
}

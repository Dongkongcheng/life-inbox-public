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
    @Update("UPDATE inbox_item SET summary = #{summary}, category = #{category} WHERE id = #{id}")
    int updateAnalysis(
            @Param("id") Long id,
            @Param("summary") String summary,
            @Param("category") String category
    );

    /**
     * 单条条件 UPDATE 同时完成领取和状态修改，避免两个请求并发启动同一条 Analyze。
     */
    @Update("""
            UPDATE inbox_item
            SET ai_status = #{processingStatus},
                ai_error_message = NULL,
                ai_started_time = CURRENT_TIMESTAMP,
                ai_finished_time = NULL
            WHERE id = #{id}
              AND ai_status <> #{processingStatus}
            """)
    int markAnalysisProcessing(
            @Param("id") Long id,
            @Param("processingStatus") AiProcessingStatus processingStatus
    );

    /** SUCCESS 与五类结果在同一个持久化事务中提交。 */
    @Update("""
            UPDATE inbox_item
            SET ai_status = #{successStatus},
                ai_error_message = NULL,
                ai_finished_time = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND ai_status = #{processingStatus}
            """)
    int markAnalysisSuccess(
            @Param("id") Long id,
            @Param("processingStatus") AiProcessingStatus processingStatus,
            @Param("successStatus") AiProcessingStatus successStatus
    );

    /** FAILED 只更新本次尝试的状态信息，不清空之前成功的 AI 结果。 */
    @Update("""
            UPDATE inbox_item
            SET ai_status = #{failedStatus},
                ai_error_message = #{errorMessage},
                ai_finished_time = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND ai_status = #{processingStatus}
            """)
    int markAnalysisFailed(
            @Param("id") Long id,
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

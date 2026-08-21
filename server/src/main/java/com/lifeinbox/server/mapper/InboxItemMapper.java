package com.lifeinbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lifeinbox.server.entity.AiProcessingStatus;
import com.lifeinbox.server.entity.InboxItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface InboxItemMapper extends BaseMapper<InboxItem> {

    /**
     * V0.2 已持久化的 Tags、Keywords、Entities 现在直接参与 Retrieve，搜索过程不调用 AI。
     * ACTIVE 条件包住全部 OR 路径，归档项不能通过 AI 关系表绕过过滤。
     * 三个 EXISTS 在数据库内完成候选判断且不会扩增主表行，避免多条分析结果产生重复 InboxItem。
     * 没有 AI 元数据时 EXISTS 只会返回 false，原有四个字段仍可独立命中。
     * 过滤条件与 ACTIVE 一起位于 OR 匹配之外，所有匹配路径都必须满足当前筛选。
     * CASE 集中表达基础相关性优先级，值只参与本次排序，不写入数据库也不暴露给前端。
     * ESCAPE 使用固定的 !，让用户输入按普通文本匹配，而不是控制 LIKE 通配范围。
     */
    @Select("""
            SELECT i.*
            FROM inbox_item i
            WHERE i.status = 'ACTIVE'
              AND (#{type,jdbcType=VARCHAR} IS NULL OR i.type = #{type,jdbcType=VARCHAR})
              AND (#{category,jdbcType=VARCHAR} IS NULL OR i.category = #{category,jdbcType=VARCHAR})
              AND (#{favorite,jdbcType=TINYINT} IS NULL OR i.favorite = #{favorite,jdbcType=TINYINT})
              AND (
                    i.title LIKE CONCAT('%', #{escapedQuery}, '%') ESCAPE '!'
                    OR i.content LIKE CONCAT('%', #{escapedQuery}, '%') ESCAPE '!'
                    OR i.summary LIKE CONCAT('%', #{escapedQuery}, '%') ESCAPE '!'
                    OR i.category LIKE CONCAT('%', #{escapedQuery}, '%') ESCAPE '!'
                    OR EXISTS (
                        SELECT 1
                        FROM inbox_tag it
                        INNER JOIN tag t ON t.id = it.tag_id
                        WHERE it.inbox_item_id = i.id
                          AND t.name LIKE CONCAT('%', #{escapedQuery}, '%') ESCAPE '!'
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM inbox_keyword ik
                        WHERE ik.inbox_item_id = i.id
                          AND ik.keyword LIKE CONCAT('%', #{escapedQuery}, '%') ESCAPE '!'
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM inbox_entity ie
                        WHERE ie.inbox_item_id = i.id
                          AND ie.name LIKE CONCAT('%', #{escapedQuery}, '%') ESCAPE '!'
                    )
              )
            ORDER BY CASE
                WHEN i.title = #{query} THEN 8
                WHEN i.title LIKE CONCAT('%', #{escapedQuery}, '%') ESCAPE '!' THEN 7
                WHEN EXISTS (
                    SELECT 1
                    FROM inbox_keyword ik_rank
                    WHERE ik_rank.inbox_item_id = i.id
                      AND ik_rank.keyword LIKE CONCAT('%', #{escapedQuery}, '%') ESCAPE '!'
                ) THEN 6
                WHEN EXISTS (
                    SELECT 1
                    FROM inbox_tag it_rank
                    INNER JOIN tag t_rank ON t_rank.id = it_rank.tag_id
                    WHERE it_rank.inbox_item_id = i.id
                      AND t_rank.name LIKE CONCAT('%', #{escapedQuery}, '%') ESCAPE '!'
                ) THEN 5
                WHEN EXISTS (
                    SELECT 1
                    FROM inbox_entity ie_rank
                    WHERE ie_rank.inbox_item_id = i.id
                      AND ie_rank.name LIKE CONCAT('%', #{escapedQuery}, '%') ESCAPE '!'
                ) THEN 4
                WHEN i.summary LIKE CONCAT('%', #{escapedQuery}, '%') ESCAPE '!' THEN 3
                WHEN i.content LIKE CONCAT('%', #{escapedQuery}, '%') ESCAPE '!' THEN 2
                WHEN i.category LIKE CONCAT('%', #{escapedQuery}, '%') ESCAPE '!' THEN 1
                ELSE 0
            END DESC,
            i.created_time DESC,
            i.id DESC
            """)
    List<InboxItem> searchActiveByKeyword(
            @Param("query") String query,
            @Param("escapedQuery") String escapedQuery,
            @Param("type") String type,
            @Param("category") String category,
            @Param("favorite") Integer favorite
    );

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

    /** 队列拒绝发生在 Attempt 创建前，只能把仍未处理的新 Capture 标记为可重试失败。 */
    @Update("""
            UPDATE inbox_item
            SET ai_status = #{failedStatus},
                ai_error_message = #{errorMessage},
                ai_started_time = NULL,
                ai_finished_time = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND ai_status = #{notProcessedStatus}
            """)
    int markAutoSchedulingFailed(
            @Param("id") Long id,
            @Param("notProcessedStatus") AiProcessingStatus notProcessedStatus,
            @Param("failedStatus") AiProcessingStatus failedStatus,
            @Param("errorMessage") String errorMessage
    );

    /** 普通 Inbox 操作只更新自己的列，避免用旧实体覆盖并发中的 AI 状态。 */
    @Update("UPDATE inbox_item SET status = #{status} WHERE id = #{id}")
    int updateInboxStatus(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE inbox_item SET favorite = #{favorite} WHERE id = #{id}")
    int updateFavorite(@Param("id") Long id, @Param("favorite") int favorite);
}

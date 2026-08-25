package com.lifeinbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lifeinbox.server.entity.ActionCandidate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ActionCandidateMapper extends BaseMapper<ActionCandidate> {

    /** 普通重新提取只能替换 AI 建议，不能覆盖未来的用户决定。 */
    @Delete("DELETE FROM action_candidate WHERE inbox_item_id = #{inboxItemId} AND status = 'PENDING'")
    int deletePendingByInboxItemId(@Param("inboxItemId") Long inboxItemId);

    @Select("""
            SELECT id, inbox_item_id, action_type, title, deadline_text, deadline_date,
                   evidence, status, created_time, updated_time
            FROM action_candidate
            WHERE inbox_item_id = #{inboxItemId}
            ORDER BY created_time ASC, id ASC
            """)
    List<ActionCandidate> selectByInboxItemId(@Param("inboxItemId") Long inboxItemId);

    /**
     * 成功替换前锁定同一 Source 的全部 Candidate，避免并发 Accept/Dismiss 后又插入完全相同建议。
     */
    @Select("""
            SELECT id, inbox_item_id, action_type, title, deadline_text, deadline_date,
                   evidence, status, created_time, updated_time
            FROM action_candidate
            WHERE inbox_item_id = #{inboxItemId}
            ORDER BY created_time ASC, id ASC
            FOR UPDATE
            """)
    List<ActionCandidate> selectByInboxItemIdForUpdate(@Param("inboxItemId") Long inboxItemId);

    /** 用户决策必须先锁定 Candidate，串行化同一条建议的并发 Accept / Dismiss。 */
    @Select("""
            SELECT id, inbox_item_id, action_type, title, deadline_text, deadline_date,
                   evidence, status, created_time, updated_time
            FROM action_candidate
            WHERE inbox_item_id = #{inboxItemId} AND id = #{candidateId}
            FOR UPDATE
            """)
    ActionCandidate selectByInboxItemIdAndIdForUpdate(
            @Param("inboxItemId") Long inboxItemId,
            @Param("candidateId") Long candidateId
    );

    @Update("UPDATE action_candidate SET status = 'ACCEPTED' "
            + "WHERE id = #{candidateId} AND status = 'PENDING'")
    int markPendingAccepted(@Param("candidateId") Long candidateId);

    @Update("UPDATE action_candidate SET status = 'DISMISSED' "
            + "WHERE id = #{candidateId} AND status = 'PENDING'")
    int markPendingDismissed(@Param("candidateId") Long candidateId);
}

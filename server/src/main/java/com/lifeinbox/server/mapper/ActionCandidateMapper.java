package com.lifeinbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lifeinbox.server.entity.ActionCandidate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}

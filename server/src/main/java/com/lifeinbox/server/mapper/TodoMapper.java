package com.lifeinbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lifeinbox.server.entity.Todo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Todo 只使用当前需要的基础持久化能力，不提前加入列表、统计或截止日期查询。 */
@Mapper
public interface TodoMapper extends BaseMapper<Todo> {

    @Select("""
            SELECT id, source_inbox_item_id, source_action_candidate_id, title, description,
                   status, due_date, completed_time, created_time, updated_time
            FROM todo
            WHERE source_action_candidate_id = #{candidateId}
            """)
    Todo selectBySourceActionCandidateId(@Param("candidateId") Long candidateId);
}

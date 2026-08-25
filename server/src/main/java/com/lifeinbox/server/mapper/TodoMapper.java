package com.lifeinbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lifeinbox.server.entity.Todo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** Todo 持久化只服务当前列表与 OPEN/COMPLETED 生命周期，不依赖来源表。 */
@Mapper
public interface TodoMapper extends BaseMapper<Todo> {

    @Select("""
            SELECT id, source_inbox_item_id, source_action_candidate_id, title, description,
                   status, due_date, completed_time, created_time, updated_time
            FROM todo
            WHERE status = 'OPEN'
            ORDER BY CASE WHEN due_date IS NULL THEN 1 ELSE 0 END ASC,
                     due_date ASC, created_time DESC, id DESC
            """)
    List<Todo> selectOpenTodos();

    @Select("""
            SELECT id, source_inbox_item_id, source_action_candidate_id, title, description,
                   status, due_date, completed_time, created_time, updated_time
            FROM todo
            WHERE status = 'COMPLETED'
            ORDER BY CASE WHEN completed_time IS NULL THEN 1 ELSE 0 END ASC,
                     completed_time DESC, created_time DESC, id DESC
            """)
    List<Todo> selectCompletedTodos();

    @Select("""
            SELECT id, source_inbox_item_id, source_action_candidate_id, title, description,
                   status, due_date, completed_time, created_time, updated_time
            FROM todo
            WHERE source_action_candidate_id = #{candidateId}
            """)
    Todo selectBySourceActionCandidateId(@Param("candidateId") Long candidateId);

    @Select("""
            SELECT id, source_inbox_item_id, source_action_candidate_id, title, description,
                   status, due_date, completed_time, created_time, updated_time
            FROM todo
            WHERE id = #{todoId}
            FOR UPDATE
            """)
    Todo selectByIdForUpdate(@Param("todoId") Long todoId);

    @Update("""
            UPDATE todo
            SET status = 'COMPLETED', completed_time = #{completedTime}
            WHERE id = #{todoId} AND status = 'OPEN'
            """)
    int markOpenCompleted(
            @Param("todoId") Long todoId,
            @Param("completedTime") LocalDateTime completedTime
    );

    @Update("""
            UPDATE todo
            SET status = 'OPEN', completed_time = NULL
            WHERE id = #{todoId}
              AND (status = 'COMPLETED' OR (status = 'OPEN' AND completed_time IS NOT NULL))
            """)
    int markReopened(@Param("todoId") Long todoId);
}

package com.lifeinbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lifeinbox.server.entity.ContentRelation;
import com.lifeinbox.server.entity.RelationType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ContentRelationMapper extends BaseMapper<ContentRelation> {

    @Select("""
            SELECT id, left_inbox_item_id, right_inbox_item_id, relation_type,
                   created_time, updated_time
            FROM content_relation
            WHERE left_inbox_item_id = #{leftInboxItemId}
              AND right_inbox_item_id = #{rightInboxItemId}
              AND relation_type = #{relationType}
            """)
    ContentRelation selectCanonicalPair(
            @Param("leftInboxItemId") Long leftInboxItemId,
            @Param("rightInboxItemId") Long rightInboxItemId,
            @Param("relationType") RelationType relationType
    );

    /** 对称关系的任一端都可能命中，不能只查询规范化 Pair 的 left 一侧。 */
    @Select("""
            SELECT id, left_inbox_item_id, right_inbox_item_id, relation_type,
                   created_time, updated_time
            FROM content_relation
            WHERE left_inbox_item_id = #{inboxItemId}
               OR right_inbox_item_id = #{inboxItemId}
            ORDER BY created_time ASC, id ASC
            """)
    List<ContentRelation> selectByInboxItemId(@Param("inboxItemId") Long inboxItemId);
}

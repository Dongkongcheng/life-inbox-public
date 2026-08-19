package com.lifeinbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lifeinbox.server.entity.InboxEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InboxEntityMapper extends BaseMapper<InboxEntity> {

    @Delete("DELETE FROM inbox_entity WHERE inbox_item_id = #{inboxItemId}")
    int deleteByInboxItemId(@Param("inboxItemId") Long inboxItemId);

    @Insert("""
            INSERT INTO inbox_entity (inbox_item_id, name, type)
            VALUES (#{inboxItemId}, #{name}, #{type})
            """)
    int insertEntity(
            @Param("inboxItemId") Long inboxItemId,
            @Param("name") String name,
            @Param("type") String type
    );

    @Select("""
            SELECT id, inbox_item_id, name, type, created_time
            FROM inbox_entity
            WHERE inbox_item_id = #{inboxItemId}
            ORDER BY id
            """)
    List<InboxEntity> selectEntitiesByInboxItemId(@Param("inboxItemId") Long inboxItemId);
}

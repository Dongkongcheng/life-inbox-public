package com.lifeinbox.server.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InboxTagMapper {

    @Delete("DELETE FROM inbox_tag WHERE inbox_item_id = #{inboxItemId}")
    int deleteByInboxItemId(@Param("inboxItemId") Long inboxItemId);

    @Insert("""
            INSERT INTO inbox_tag (inbox_item_id, tag_id)
            VALUES (#{inboxItemId}, #{tagId})
            """)
    int insertRelation(
            @Param("inboxItemId") Long inboxItemId,
            @Param("tagId") Long tagId
    );

    @Select("""
            SELECT t.name
            FROM tag t
            INNER JOIN inbox_tag it ON it.tag_id = t.id
            WHERE it.inbox_item_id = #{inboxItemId}
            ORDER BY t.name
            """)
    List<String> selectTagNamesByInboxItemId(@Param("inboxItemId") Long inboxItemId);
}

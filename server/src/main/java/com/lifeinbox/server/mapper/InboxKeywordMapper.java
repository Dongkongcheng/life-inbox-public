package com.lifeinbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lifeinbox.server.entity.InboxKeyword;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InboxKeywordMapper extends BaseMapper<InboxKeyword> {

    @Delete("DELETE FROM inbox_keyword WHERE inbox_item_id = #{inboxItemId}")
    int deleteByInboxItemId(@Param("inboxItemId") Long inboxItemId);

    @Insert("INSERT INTO inbox_keyword (inbox_item_id, keyword) VALUES (#{inboxItemId}, #{keyword})")
    int insertKeyword(
            @Param("inboxItemId") Long inboxItemId,
            @Param("keyword") String keyword
    );

    /** 自增 id 与顺序插入共同保留本次模型结果的展示顺序。 */
    @Select("SELECT keyword FROM inbox_keyword WHERE inbox_item_id = #{inboxItemId} ORDER BY id")
    List<String> selectKeywordsByInboxItemId(@Param("inboxItemId") Long inboxItemId);
}

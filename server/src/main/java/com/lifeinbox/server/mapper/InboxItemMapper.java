package com.lifeinbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lifeinbox.server.entity.InboxItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InboxItemMapper extends BaseMapper<InboxItem> {

    /** 只更新摘要，避免慢速 AI 调用后用旧实体覆盖收藏、归档等并发变化。 */
    @Update("UPDATE inbox_item SET summary = #{summary} WHERE id = #{id}")
    int updateSummary(@Param("id") Long id, @Param("summary") String summary);
}

package com.lifeinbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lifeinbox.server.entity.InboxItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InboxItemMapper extends BaseMapper<InboxItem> {

    /**
     * 只更新本次 AI 分析拥有的列；同时取得该 InboxItem 的行锁，串行化并发重分析。
     */
    @Update("UPDATE inbox_item SET summary = #{summary}, category = #{category} WHERE id = #{id}")
    int updateAnalysis(
            @Param("id") Long id,
            @Param("summary") String summary,
            @Param("category") String category
    );
}

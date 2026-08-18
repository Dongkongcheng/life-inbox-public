package com.lifeinbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lifeinbox.server.entity.Tag;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TagMapper extends BaseMapper<Tag> {

    /** 唯一键处理并发创建；重复时保留最早写入的展示名称。 */
    @Insert("""
            INSERT INTO tag (name, normalized_name)
            VALUES (#{name}, #{normalizedName})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int upsertTag(
            @Param("name") String name,
            @Param("normalizedName") String normalizedName
    );

    @Select("SELECT id FROM tag WHERE normalized_name = #{normalizedName}")
    Long selectIdByNormalizedName(@Param("normalizedName") String normalizedName);
}

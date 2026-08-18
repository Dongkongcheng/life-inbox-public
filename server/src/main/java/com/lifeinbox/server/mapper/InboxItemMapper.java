package com.lifeinbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lifeinbox.server.entity.InboxItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InboxItemMapper extends BaseMapper<InboxItem> {
}
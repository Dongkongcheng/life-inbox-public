package com.lifeinbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lifeinbox.server.entity.Todo;
import org.apache.ibatis.annotations.Mapper;

/** Todo 只使用当前需要的基础持久化能力，不提前加入列表、统计或截止日期查询。 */
@Mapper
public interface TodoMapper extends BaseMapper<Todo> {
}

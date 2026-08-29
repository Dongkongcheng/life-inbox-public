package com.lifeinbox.server.entity;

import com.baomidou.mybatisplus.annotation.IEnum;

/** Relation Discovery 独立于 Analyze 和 Action 的处理状态。 */
public enum RelationProcessingStatus implements IEnum<String> {

    NOT_PROCESSED,
    PROCESSING,
    SUCCESS,
    FAILED;

    @Override
    public String getValue() {
        return name();
    }
}

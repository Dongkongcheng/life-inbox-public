package com.lifeinbox.server.entity;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * InboxItem 最近一次 AI Analyze 的业务状态。
 * 状态由 Java 管理并存入 MySQL，Python 只负责返回分析结果或错误。
 */
public enum AiProcessingStatus implements IEnum<String> {
    NOT_PROCESSED,
    PROCESSING,
    SUCCESS,
    FAILED;

    @Override
    public String getValue() {
        return name();
    }
}

package com.lifeinbox.server.entity;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * Action Extraction 的独立处理状态，不能复用 Analyze 的 ai_status。
 * Candidate 的 PENDING/ACCEPTED/DISMISSED 仍表示另一套用户决策状态机。
 */
public enum ActionProcessingStatus implements IEnum<String> {
    NOT_PROCESSED,
    PROCESSING,
    SUCCESS,
    FAILED;

    @Override
    public String getValue() {
        return name();
    }
}

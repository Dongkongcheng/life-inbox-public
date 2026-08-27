package com.lifeinbox.server.service;

/** 集中定义 Java → Python Relation Discovery 的确定性输入边界。 */
public final class RelationDiscoveryLimits {

    public static final int MAX_CANDIDATES = 20;
    public static final int MAX_SOURCE_TEXT_CHARS = 4_000;
    public static final int MAX_CANDIDATE_TEXT_CHARS = 1_000;
    public static final int MAX_TOTAL_TEXT_CHARS = MAX_SOURCE_TEXT_CHARS
            + MAX_CANDIDATES * MAX_CANDIDATE_TEXT_CHARS;

    private RelationDiscoveryLimits() {
    }
}

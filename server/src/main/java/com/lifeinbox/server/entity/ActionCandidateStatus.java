package com.lifeinbox.server.entity;

/** Candidate 仍是建议；Task 32 只创建 PENDING，另外两种状态留给后续用户决策。 */
public enum ActionCandidateStatus {
    PENDING,
    ACCEPTED,
    DISMISSED
}

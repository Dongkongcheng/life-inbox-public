package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.RelationDiscoveryCandidate;

import java.util.List;

/** Task 47 区分“Vector 未就绪”和“已就绪但没有候选”，避免错误标记 SUCCESS。 */
public record RelationCandidateDiscoveryResult(
        boolean sourceIndexed,
        List<RelationDiscoveryCandidate> candidates
) {
    public RelationCandidateDiscoveryResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}

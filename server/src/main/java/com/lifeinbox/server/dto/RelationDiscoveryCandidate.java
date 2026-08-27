package com.lifeinbox.server.dto;

/** Task 42 的瞬时候选；Semantic Score 不进入 MySQL，也不等于正式 Relation。 */
public record RelationDiscoveryCandidate(Long inboxItemId, double semanticScore) {
}

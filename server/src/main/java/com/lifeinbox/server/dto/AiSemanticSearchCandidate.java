package com.lifeinbox.server.dto;

/** Qdrant 的运行时候选；Score 仅用于维持语义顺序，不进入 InboxItem。 */
public record AiSemanticSearchCandidate(Long inboxItemId, Double score) {
}

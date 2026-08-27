package com.lifeinbox.server.dto;

/** 发送给 Python/LLM 的最小 Relation 文本单元，不包含 Semantic Score。 */
public record AiRelationDiscoveryItem(Long inboxItemId, String text) {
}

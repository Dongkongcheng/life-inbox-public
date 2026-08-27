package com.lifeinbox.server.dto;

import java.util.List;

/** 一次批量发送 Source 与有限候选，避免逐候选调用 LLM。 */
public record AiRelationDiscoveryRequest(
        AiRelationDiscoveryItem source,
        List<AiRelationDiscoveryItem> candidates
) {
}

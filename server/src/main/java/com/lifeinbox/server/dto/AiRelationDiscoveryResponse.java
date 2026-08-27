package com.lifeinbox.server.dto;

import java.util.List;

/** Python 只返回确认相关的候选 ID；空列表也是成功结果。 */
public record AiRelationDiscoveryResponse(List<Long> relatedTargetInboxItemIds) {
}

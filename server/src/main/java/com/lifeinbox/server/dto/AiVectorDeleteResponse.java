package com.lifeinbox.server.dto;

/** Point 删除是内部、幂等且可降级的派生索引操作。 */
public record AiVectorDeleteResponse(Long inboxItemId, boolean deleted) {
}

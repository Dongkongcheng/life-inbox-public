package com.lifeinbox.server.dto;

/** AI 提取的明确实体；type 由有限集合约束，避免模型自由创造类型。 */
public record AiEntityResponse(String name, String type) {
}

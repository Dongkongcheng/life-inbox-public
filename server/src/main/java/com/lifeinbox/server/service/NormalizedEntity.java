package com.lifeinbox.server.service;

/** 已完成名称规范化、去重和有限类型校验的实体。 */
public record NormalizedEntity(String name, String type) {
}

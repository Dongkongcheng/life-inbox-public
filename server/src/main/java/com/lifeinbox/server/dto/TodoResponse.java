package com.lifeinbox.server.dto;

import com.lifeinbox.server.entity.TodoStatus;

import java.time.LocalDate;

/** Candidate Accept 的最小 Todo 产品响应，不直接暴露持久化 Entity。 */
public record TodoResponse(
        Long id,
        String title,
        TodoStatus status,
        LocalDate dueDate
) {
}

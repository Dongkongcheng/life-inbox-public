package com.lifeinbox.server.dto;

import com.lifeinbox.server.entity.Todo;
import com.lifeinbox.server.entity.TodoStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Todo 产品响应；保留可空来源 ID，但不 JOIN 或展开来源详情。 */
public record TodoResponse(
        Long id,
        Long sourceInboxItemId,
        Long sourceActionCandidateId,
        String title,
        String description,
        TodoStatus status,
        LocalDate dueDate,
        LocalDateTime completedTime,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {

    public static TodoResponse from(Todo todo) {
        return new TodoResponse(
                todo.getId(),
                todo.getSourceInboxItemId(),
                todo.getSourceActionCandidateId(),
                todo.getTitle(),
                todo.getDescription(),
                todo.getStatus(),
                todo.getDueDate(),
                todo.getCompletedTime(),
                todo.getCreatedTime(),
                todo.getUpdatedTime()
        );
    }
}

package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.Todo;
import com.lifeinbox.server.entity.TodoStatus;
import com.lifeinbox.server.mapper.TodoMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

/** Todo 是 Java/MySQL 拥有的独立业务状态；当前只提供内部创建与按 ID 读取能力。 */
@Service
public class TodoService {

    static final int MAX_TITLE_CHARS = 255;

    private final TodoMapper todoMapper;

    public TodoService(TodoMapper todoMapper) {
        this.todoMapper = todoMapper;
    }

    /**
     * 新 Todo 始终从 OPEN 开始，完成状态不能由未来 Candidate 或任意调用方注入。
     * 来源字段只保留追溯关系，不复制 Candidate evidence、actionType 或 Inbox 正文。
     */
    @Transactional
    public Todo create(
            String title,
            String description,
            LocalDate dueDate,
            Long sourceInboxItemId,
            Long sourceActionCandidateId
    ) {
        Todo todo = new Todo();
        todo.setTitle(normalizeTitle(title));
        todo.setDescription(normalizeDescription(description));
        todo.setDueDate(dueDate);
        todo.setSourceInboxItemId(sourceInboxItemId);
        todo.setSourceActionCandidateId(sourceActionCandidateId);
        todo.setStatus(TodoStatus.OPEN);
        todo.setCompletedTime(null);

        try {
            if (todoMapper.insert(todo) != 1) {
                throw saveFailure();
            }
        } catch (DuplicateKeyException exception) {
            // 数据库唯一约束是并发下最终防线，保证同一 Candidate 最多生成一个 Todo。
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Action Candidate 已经关联 Todo",
                    exception
            );
        }

        Todo saved = todoMapper.selectById(todo.getId());
        if (saved == null) {
            throw saveFailure();
        }
        return saved;
    }

    public Todo get(Long todoId) {
        if (todoId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Todo ID 不能为空");
        }
        Todo todo = todoMapper.selectById(todoId);
        if (todo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Todo 不存在");
        }
        return todo;
    }

    private String normalizeTitle(String title) {
        if (title == null) {
            throw invalidTitle();
        }
        String normalized = title.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_TITLE_CHARS) {
            throw invalidTitle();
        }
        return normalized;
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String normalized = description.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private ResponseStatusException invalidTitle() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Todo 标题不能为空且长度不能超过 255"
        );
    }

    private ResponseStatusException saveFailure() {
        return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Todo 保存失败"
        );
    }
}

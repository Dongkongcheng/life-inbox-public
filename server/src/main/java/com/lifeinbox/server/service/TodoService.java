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
import java.time.LocalDateTime;
import java.util.List;

/** Todo 是 Java/MySQL 拥有的独立业务状态；AI Candidate 不能反向控制其生命周期。 */
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
        requireTodoId(todoId);
        Todo todo = todoMapper.selectById(todoId);
        if (todo == null) {
            throw notFound();
        }
        return todo;
    }

    /** 默认展示 OPEN；枚举解析留在业务层，任意字符串不会进入 SQL。 */
    public List<Todo> list(String requestedStatus) {
        TodoStatus status = parseStatus(requestedStatus);
        List<Todo> todos = status == TodoStatus.OPEN
                ? todoMapper.selectOpenTodos()
                : todoMapper.selectCompletedTodos();
        if (todos == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Todo 列表查询失败"
            );
        }
        return List.copyOf(todos);
    }

    /**
     * 完成时间只在第一次有效 OPEN → COMPLETED 时由 Java 生成；重复 Complete 不刷新它。
     * 行锁使同一 Todo 的 Complete/Reopen 在单库场景下按数据库顺序串行执行。
     */
    @Transactional
    public Todo complete(Long todoId) {
        Todo current = requireForUpdate(todoId);
        TodoStatus status = requireKnownStatus(current);
        if (status == TodoStatus.COMPLETED) {
            if (current.getCompletedTime() == null) {
                throw integrityFailure();
            }
            return current;
        }

        LocalDateTime completedTime = LocalDateTime.now();
        if (todoMapper.markOpenCompleted(todoId, completedTime) != 1) {
            throw updateFailure();
        }
        return requireUpdated(todoId, TodoStatus.COMPLETED);
    }

    /** Reopen 必须清空完成时间，避免 OPEN 与 completed_time 同时存在的矛盾状态。 */
    @Transactional
    public Todo reopen(Long todoId) {
        Todo current = requireForUpdate(todoId);
        TodoStatus status = requireKnownStatus(current);
        if (status == TodoStatus.OPEN && current.getCompletedTime() == null) {
            return current;
        }

        if (todoMapper.markReopened(todoId) != 1) {
            throw updateFailure();
        }
        return requireUpdated(todoId, TodoStatus.OPEN);
    }

    /** Candidate 重复 Accept 时按唯一来源查回原 Todo，不把 SQL 冲突当作正常幂等流程。 */
    public Todo findBySourceActionCandidateId(Long candidateId) {
        return todoMapper.selectBySourceActionCandidateId(candidateId);
    }

    private TodoStatus parseStatus(String requestedStatus) {
        if (requestedStatus == null) {
            return TodoStatus.OPEN;
        }
        try {
            return TodoStatus.valueOf(requestedStatus);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Todo 状态只允许 OPEN 或 COMPLETED"
            );
        }
    }

    private Todo requireForUpdate(Long todoId) {
        requireTodoId(todoId);
        Todo todo = todoMapper.selectByIdForUpdate(todoId);
        if (todo == null) {
            throw notFound();
        }
        return todo;
    }

    private Todo requireUpdated(Long todoId, TodoStatus expectedStatus) {
        Todo updated = todoMapper.selectById(todoId);
        if (updated == null || updated.getStatus() != expectedStatus) {
            throw integrityFailure();
        }
        if ((expectedStatus == TodoStatus.OPEN && updated.getCompletedTime() != null)
                || (expectedStatus == TodoStatus.COMPLETED
                && updated.getCompletedTime() == null)) {
            throw integrityFailure();
        }
        return updated;
    }

    private TodoStatus requireKnownStatus(Todo todo) {
        if (todo.getStatus() == null) {
            throw integrityFailure();
        }
        return todo.getStatus();
    }

    private void requireTodoId(Long todoId) {
        if (todoId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Todo ID 不能为空");
        }
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

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Todo 不存在");
    }

    private ResponseStatusException updateFailure() {
        return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Todo 状态更新失败"
        );
    }

    private ResponseStatusException integrityFailure() {
        return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Todo 数据状态不完整"
        );
    }
}

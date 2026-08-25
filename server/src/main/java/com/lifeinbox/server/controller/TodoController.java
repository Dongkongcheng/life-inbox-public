package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.TodoResponse;
import com.lifeinbox.server.service.TodoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Todo 产品 API 使用明确业务动作，避免客户端任意写入 status 或 completedTime。 */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoService todoService;

    public TodoController(TodoService todoService) {
        this.todoService = todoService;
    }

    @GetMapping
    public List<TodoResponse> list(@RequestParam(required = false) String status) {
        return todoService.list(status).stream().map(TodoResponse::from).toList();
    }

    @PostMapping("/{todoId}/complete")
    public TodoResponse complete(@PathVariable Long todoId) {
        return TodoResponse.from(todoService.complete(todoId));
    }

    @PostMapping("/{todoId}/reopen")
    public TodoResponse reopen(@PathVariable Long todoId) {
        return TodoResponse.from(todoService.reopen(todoId));
    }
}

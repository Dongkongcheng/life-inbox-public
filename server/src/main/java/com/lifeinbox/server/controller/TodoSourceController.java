package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.TodoSourceResponse;
import com.lifeinbox.server.service.TodoSourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 来源详情使用独立按需 API，避免 Todo 列表产生 Source JOIN 或逐条查询。 */
@RestController
@RequestMapping("/api/todos")
public class TodoSourceController {

    private final TodoSourceService todoSourceService;

    public TodoSourceController(TodoSourceService todoSourceService) {
        this.todoSourceService = todoSourceService;
    }

    @GetMapping("/{todoId}/source")
    public TodoSourceResponse getSource(@PathVariable Long todoId) {
        return todoSourceService.getSource(todoId);
    }
}

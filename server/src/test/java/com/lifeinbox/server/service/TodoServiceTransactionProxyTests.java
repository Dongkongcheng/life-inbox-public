package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.Todo;
import com.lifeinbox.server.entity.TodoStatus;
import com.lifeinbox.server.mapper.TodoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 使用真实 Spring 代理确认 Complete/Reopen 各自在短事务内提交。 */
@SpringJUnitConfig(TodoServiceTransactionProxyTests.TestConfiguration.class)
class TodoServiceTransactionProxyTests {

    @Autowired
    private TodoService todoService;

    @Autowired
    private TodoMapper todoMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetMocks() {
        reset(todoMapper, transactionManager);
    }

    @Test
    void completeRunsThroughTransactionProxy() {
        SimpleTransactionStatus transaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transaction);
        Todo open = todo(1L, TodoStatus.OPEN, null);
        Todo completed = todo(
                1L,
                TodoStatus.COMPLETED,
                LocalDateTime.of(2026, 8, 25, 12, 0)
        );
        when(todoMapper.selectByIdForUpdate(1L)).thenReturn(open);
        when(todoMapper.markOpenCompleted(eq(1L), any(LocalDateTime.class))).thenReturn(1);
        when(todoMapper.selectById(1L)).thenReturn(completed);

        assertEquals(TodoStatus.COMPLETED, todoService.complete(1L).getStatus());

        verify(transactionManager).commit(transaction);
    }

    @Test
    void reopenRunsThroughTransactionProxy() {
        SimpleTransactionStatus transaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transaction);
        Todo completed = todo(
                2L,
                TodoStatus.COMPLETED,
                LocalDateTime.of(2026, 8, 25, 12, 0)
        );
        Todo open = todo(2L, TodoStatus.OPEN, null);
        when(todoMapper.selectByIdForUpdate(2L)).thenReturn(completed);
        when(todoMapper.markReopened(2L)).thenReturn(1);
        when(todoMapper.selectById(2L)).thenReturn(open);

        Todo reopened = todoService.reopen(2L);

        assertEquals(TodoStatus.OPEN, reopened.getStatus());
        assertNull(reopened.getCompletedTime());
        verify(transactionManager).commit(transaction);
    }

    private Todo todo(Long id, TodoStatus status, LocalDateTime completedTime) {
        Todo todo = new Todo();
        todo.setId(id);
        todo.setTitle("Todo " + id);
        todo.setStatus(status);
        todo.setCompletedTime(completedTime);
        return todo;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfiguration {

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        TodoMapper todoMapper() {
            return mock(TodoMapper.class);
        }

        @Bean
        TodoService todoService(TodoMapper todoMapper) {
            return new TodoService(todoMapper);
        }
    }
}
